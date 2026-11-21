"""Run the actual Partidas Redis/Kafka runtime against disposable H04 services."""
import asyncio
import json
import os
import queue
import subprocess
import sys
import threading
import time
import uuid

import redis

from app.application.timed_matches import FinishTimedMatch
from app.messaging.match_events import match_finished_event
from app.messaging.match_saga_runtime import MatchSagaRuntime
from app.repositories.matches import MatchRepository


PROJECT = os.environ["H04_COMPOSE_PROJECT"]
COMPOSE = os.environ["H04_COMPOSE_FILE"]
ROOT = os.environ["H04_PARTIDAS_ROOT"]
BOOTSTRAP = os.environ["H04_KAFKA_BOOTSTRAP"]
PROCESSES = []
LOG_QUEUE = queue.Queue()
RUNTIME = None


def compose(*args, check=True, timeout=60):
    result = subprocess.run(
        ["docker", "compose", "-p", PROJECT, "-f", COMPOSE, *args],
        text=True, capture_output=True, timeout=timeout,
    )
    if check and result.returncode:
        raise RuntimeError("isolated compose operation failed: " + " ".join(args[:2]))
    return result


def sql(statement):
    result = compose("exec", "-T", "postgres", "psql", "-X", "-qAt", "-U",
                     "postgres", "-d", os.environ["H04_DATABASE_NAME"], "-c", statement)
    return result.stdout.strip()


def pump(process):
    for line in process.stdout:
        if "Started UsersServiceApplication" in line:
            LOG_QUEUE.put((process, "started"))
        if "Subscribed to topic(s): matches.finished.v1" in line:
            LOG_QUEUE.put((process, "subscribed"))
        if "ERROR" in line or "Exception" in line or "procesado eventId=" in line:
            LOG_QUEUE.put((process, line.rstrip()[:500]))


def start_users(delay):
    env = os.environ.copy()
    env["MATCH_SAGA_OUTBOX_INITIAL_DELAY_MS"] = str(delay)
    process = subprocess.Popen(["java", "-jar", env["H04_USERS_JAR"],
                                "--events.saga-outbox.initial-delay-ms=" + str(delay)], env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace")
    PROCESSES.append(process)
    threading.Thread(target=pump, args=(process,), daemon=True).start()
    deadline = time.monotonic() + 100
    started = False
    subscribed = False
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("isolated Users process exited during startup")
        try:
            owner, item = LOG_QUEUE.get(timeout=1)
            if owner is process:
                started = started or item == "started"
                subscribed = subscribed or item == "subscribed"
                if started and subscribed:
                    return process
        except queue.Empty:
            pass
    raise RuntimeError("isolated Users Kafka listener did not subscribe before timeout")


def wait_broker_ready(seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-broker-api-versions.sh",
                         "--bootstrap-server", "kafka:9092", check=False, timeout=8)
        if result.returncode == 0:
            return
        time.sleep(1)
    raise RuntimeError("disposable Kafka did not become usable after restart")


def stop_users(process):
    if process.poll() is None:
        process.kill()
        process.wait(timeout=20)


class FixedClock:
    def __init__(self, now):
        self.now = now

    def now_seconds(self):
        return self.now


class CorrectCase:
    def validate(self, _case_id, _positions):
        return True


def make_match(repo, match_id, user_id, now=None):
    now = now or int(time.time())
    repo.save_match(match_id, {
        "matchId": match_id, "caseId": "h04-case", "startedAt": now - 1,
        "deadline": now + 100, "mode": "TIMED", "status": "IN_PROGRESS",
        "player1": user_id,
    })
    finish = FinishTimedMatch(repo, CorrectCase(), None, FixedClock(now))
    outcome = finish.execute(match_id, user_id, [])
    if outcome.get("sagaStatus") != "PENDING":
        raise AssertionError("Partidas did not persist a pending terminal result")
    return outcome


def complete_from_event(repo, match_id, mode, players):
    event = match_finished_event(match_id, "h04-versus-case", mode, 42, players)
    repo.save_match(match_id, {
        "matchId": match_id, "caseId": "h04-versus-case", "startedAt": int(time.time()) - 42,
        "deadline": int(time.time()), "mode": mode, "status": "IN_PROGRESS",
        "player1": players[0]["userId"], "player2": players[-1]["userId"],
    })
    final_match = {
        "matchId": match_id, "caseId": "h04-versus-case", "mode": mode,
        "status": "FINISHED", "sagaStatus": "PENDING", "sagaEventId": event["eventId"],
        "correlationId": event["correlationId"], "finishedAt": int(time.time()),
        "matchFinishedEvent": event,
    }
    if not repo.complete_match(match_id, "IN_PROGRESS", final_match):
        raise AssertionError("Partidas repository refused a terminal event")
    return event


async def wait_until(predicate, label, seconds=50):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        await asyncio.sleep(.25)
    raise AssertionError("timed out waiting for " + label)


def db_scalar(query):
    value = sql(query)
    if not value:
        return 0
    return int(value)


async def start_audit_consumer():
    from aiokafka import AIOKafkaConsumer

    consumer = AIOKafkaConsumer(
        "matches.saga.confirmed.v1", bootstrap_servers=BOOTSTRAP,
        group_id="h04-audit-" + uuid.uuid4().hex, enable_auto_commit=False,
        auto_offset_reset="latest",
    )
    await consumer.start()
    deadline = time.monotonic() + 20
    while not consumer.assignment() and time.monotonic() < deadline:
        await consumer.getmany(timeout_ms=500)
    if not consumer.assignment():
        await consumer.stop()
        raise RuntimeError("Kafka outcome audit consumer did not receive a partition")
    return consumer


async def audited_outcomes(consumer, match_id, seconds=3):
    outcomes = []
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            message = await asyncio.wait_for(consumer.getone(), timeout=.5)
        except asyncio.TimeoutError:
            continue
        event = json.loads(message.value)
        if event.get("payload", {}).get("matchId") == match_id:
            outcomes.append(event)
    return outcomes


async def scenario_b(repository, users):
    outage_id = "h04-outage-" + uuid.uuid4().hex[:8]
    if repository.get_match(outage_id) is not None or db_scalar(
            "SELECT count(*) FROM match_saga_outbox WHERE match_id='" + outage_id + "'"):
        raise AssertionError("B did not start from clean Redis/PostgreSQL state")
    make_match(repository, outage_id, "h04-player-outage")
    await wait_until(lambda: db_scalar("SELECT count(*) FROM match_saga_outbox WHERE match_id='" + outage_id + "' AND status='PENDING'") == 1,
                     "B source event committed to a pending outbox")
    before = sql("SELECT attempts || ':' || to_char(updated_at,'YYYY-MM-DD HH24:MI:SS.US') FROM match_saga_outbox WHERE match_id='" + outage_id + "' AND status='PENDING'")
    if not before:
        raise AssertionError("B publisher barrier failed: the row was no longer pending before Kafka stop")
    stop_started = time.time()
    compose("stop", "kafka")
    running = compose("ps", "--status", "running", "-q", "kafka", check=False)
    if running.stdout.strip():
        raise AssertionError("B broker was still running after the isolated stop barrier")
    during = sql("SELECT status || ':' || attempts || ':' || to_char(updated_at,'YYYY-MM-DD HH24:MI:SS.US') FROM match_saga_outbox WHERE match_id='" + outage_id + "'")
    if during != "PENDING:" + before:
        raise AssertionError("B outbox changed while Kafka was stopped: " + during)
    if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + outage_id + "'") != 1:
        raise AssertionError("B Kafka outage changed the committed result")
    compose("start", "kafka")
    compose("up", "-d", "--wait", "kafka")
    wait_broker_ready()
    stop_users(users)
    users = start_users(100)
    await wait_until(lambda: repository.get_match(outage_id).get("sagaStatus") == "CONFIRMED", "B recovered confirmation", 90)
    if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + outage_id + "'") != 1 or db_scalar(
            "SELECT count(*) FROM match_saga_outbox WHERE match_id='" + outage_id + "' AND status='PUBLISHED' AND attempts >= 1") != 1:
        raise AssertionError("B did not recover one result and its pending confirmation")
    print("PASS B isolated Kafka outage and durable outbox recovery ({:.3f}s)".format(time.time() - stop_started))
    return users


async def main():
    global RUNTIME
    requested = {item.strip().upper() for item in os.getenv("H04_SCENARIOS", "ALL").split(",") if item.strip()}
    run_all = not requested or "ALL" in requested
    selected = lambda name: run_all or name in requested
    client = redis.Redis(host="127.0.0.1", port=int(os.environ["H04_REDIS_PORT"]), decode_responses=True)
    client.ping()
    repository = MatchRepository(client)
    runtime = MatchSagaRuntime(repository)
    RUNTIME = runtime
    os.environ["KAFKA_BOOTSTRAP_SERVERS"] = BOOTSTRAP
    await runtime.start()
    await wait_until(lambda: runtime._consumer is not None, "Partidas Kafka consumer")

    # A long scheduling barrier guarantees B can observe durable PENDING before any publish attempt.
    initial_delay = 86400000 if selected("B") or selected("E") else 100
    users = start_users(initial_delay)

    if selected("B"):
        users = await scenario_b(repository, users)

    if selected("E"):
        if selected("B"):
            stop_users(users)
            users = start_users(86400000)
        restart_id = "h04-restart-" + uuid.uuid4().hex[:8]
        make_match(repository, restart_id, "h04-player-restart")
        await wait_until(lambda: db_scalar("SELECT count(*) FROM match_saga_outbox WHERE match_id='" + restart_id + "'") == 1,
                         "transactional PostgreSQL result and outbox")
        if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + restart_id + "'") != 1 or db_scalar(
                "SELECT count(*) FROM match_saga_outbox WHERE match_id='" + restart_id + "' AND status='PENDING'") != 1:
            raise AssertionError("result and pending outbox were not committed together")
        stop_users(users)
        users = start_users(100)
        await wait_until(lambda: repository.get_match(restart_id).get("sagaStatus") == "CONFIRMED", "confirmation after Users restart")
        if db_scalar("SELECT count(*) FROM match_saga_outbox WHERE match_id='" + restart_id + "' AND status='PUBLISHED'") != 1:
            raise AssertionError("restart did not recover the pending outbox row")
        user = repository.get_match(restart_id)["sagaResults"][0]
        if float(client.zscore("ranking:global", "h04-player-restart") or 0) != user["xpDelta"]:
            raise AssertionError("recovered outcome applied incorrect Redis XP")
        history_size = client.llen("user:h04-player-restart:history")
        payload = sql("SELECT payload FROM match_saga_outbox WHERE match_id='" + restart_id + "'")
        from aiokafka import AIOKafkaProducer
        duplicate_producer = AIOKafkaProducer(bootstrap_servers=BOOTSTRAP, acks="all")
        await duplicate_producer.start()
        try:
            await duplicate_producer.send_and_wait("matches.saga.confirmed.v1", key=restart_id.encode(), value=payload.encode())
        finally:
            await duplicate_producer.stop()
        await asyncio.sleep(2)
        if client.llen("user:h04-player-restart:history") != history_size:
            raise AssertionError("duplicate outcome changed Redis history")
        print("PASS E restart recovery and duplicate outcome idempotency")

    if selected("A"):
        match_id = "h04-flow-" + uuid.uuid4().hex[:8]
        result = make_match(repository, match_id, "h04-player-flow")
        await wait_until(lambda: repository.get_match(match_id).get("sagaStatus") == "CONFIRMED", "normal saga completion")
        if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + match_id + "'") != 1 or db_scalar(
                "SELECT count(*) FROM match_saga_outbox WHERE match_id='" + match_id + "' AND status='PUBLISHED'") != 1:
            raise AssertionError("normal result/outbox was not exactly-once persisted")
        if float(client.zscore("ranking:global", "h04-player-flow") or 0) != result["xpDelta"]:
            raise AssertionError("normal Redis ranking is inconsistent")
        print("PASS A normal end-to-end saga")

    if selected("C"):
        lease_id = "h04-lease-" + uuid.uuid4().hex[:8]
        sql("CREATE OR REPLACE FUNCTION h04_delay_publish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            "IF NEW.status='PUBLISHED' AND OLD.status='PENDING' THEN PERFORM pg_sleep(30); END IF; RETURN NEW; END $$; "
            "CREATE TRIGGER h04_delay_publish BEFORE UPDATE ON match_saga_outbox FOR EACH ROW EXECUTE FUNCTION h04_delay_publish()")
        make_match(repository, lease_id, "h04-player-lease")
        await wait_until(lambda: repository.get_match(lease_id).get("sagaStatus") == "CONFIRMED", "C Partidas applies first outcome", 60)
        await wait_until(lambda: "PgSleep" in sql("SELECT coalesce(wait_event,'') FROM pg_stat_activity WHERE datname=current_database() AND query LIKE 'UPDATE match_saga_outbox%' AND state='active'"),
                         "C publisher blocks after Kafka ack", 20)
        score_before = float(client.zscore("ranking:global", "h04-player-lease") or 0)
        history_before = client.llen("user:h04-player-lease:history")
        stop_users(users)
        sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND query LIKE 'UPDATE match_saga_outbox%' AND state='active'")
        await wait_until(lambda: not sql("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND query LIKE 'UPDATE match_saga_outbox%' AND state='active'"), "C ack transaction rollback", 10)
        sql("DROP TRIGGER h04_delay_publish ON match_saga_outbox; DROP FUNCTION h04_delay_publish()")
        users = start_users(100)
        await wait_until(lambda: db_scalar("SELECT count(*) FROM match_saga_outbox WHERE match_id='" + lease_id + "' AND status='PUBLISHED' AND attempts >= 2") == 1,
                         "C expired lease is republished", 40)
        if float(client.zscore("ranking:global", "h04-player-lease") or 0) != score_before or client.llen("user:h04-player-lease:history") != history_before:
            raise AssertionError("C duplicate did not remain idempotent in Redis")
        print("PASS C publish/ack crash window and lease fencing")

    if selected("D"):
        match_id = "h04-commitfail-" + uuid.uuid4().hex[:8]
        audit = await start_audit_consumer()
        sql("CREATE OR REPLACE FUNCTION h04_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            "IF NEW.match_id LIKE 'h04-commitfail-%' THEN RAISE EXCEPTION 'isolated commit fault'; END IF; RETURN NEW; END $$; "
            "CREATE CONSTRAINT TRIGGER h04_fail_commit AFTER INSERT ON match_saga_outbox DEFERRABLE INITIALLY DEFERRED "
            "FOR EACH ROW EXECUTE FUNCTION h04_fail_commit()")
        make_match(repository, match_id, "h04-player-commitfail")
        await wait_until(lambda: repository.get_match(match_id).get("sagaStatus") == "CANCELLED", "D failed commit reaches CANCELLED", 70)
        outcomes = await audited_outcomes(audit, match_id)
        await audit.stop()
        sql("DROP TRIGGER h04_fail_commit ON match_saga_outbox; DROP FUNCTION h04_fail_commit()")
        if not outcomes or any(outcome["payload"]["status"] == "CONFIRMED" for outcome in outcomes) or not all(
                outcome["payload"]["status"] == "CANCELLED" for outcome in outcomes):
            raise AssertionError("D Kafka audit did not show only cancellation outcomes")
        if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + match_id + "'") or db_scalar(
                "SELECT count(*) FROM classic_case_rewards WHERE case_id='h04-case' AND user_id IN "
                "(SELECT id FROM users WHERE external_id='h04-player-commitfail')") or db_scalar(
                "SELECT count(*) FROM match_saga_outbox WHERE match_id='" + match_id + "'"):
            raise AssertionError("D failed transaction retained a result, reward, or outbox confirmation")
        if db_scalar("SELECT count(*) FROM users WHERE external_id='h04-player-commitfail' AND experience=0") != 1:
            raise AssertionError("D profile XP changed despite failed result transaction")
        print("PASS D deferred commit fault rolls back results/rewards/outbox; Kafka and Redis are not CONFIRMED")

    if selected("F1") or selected("F"):
        match_id = "h04-versus-" + uuid.uuid4().hex[:8]
        audit = await start_audit_consumer()
        complete_from_event(repository, match_id, "VERSUS", [
            {"userId": "h04-versus-winner", "won": True, "xpDelta": 100, "difficulty": None},
            {"userId": "h04-versus-loser", "won": False, "xpDelta": 0, "difficulty": None},
        ])
        await wait_until(lambda: repository.get_match(match_id).get("sagaStatus") == "CONFIRMED", "F1 VERSUS confirmation")
        outcomes = await audited_outcomes(audit, match_id)
        if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + match_id + "'") != 2 or db_scalar(
                "SELECT count(*) FROM match_saga_outbox WHERE match_id='" + match_id + "' AND status='PUBLISHED'") != 1:
            raise AssertionError("F1 PostgreSQL did not persist two results and one published outcome")
        confirmation = json.loads(sql("SELECT payload FROM match_saga_outbox WHERE match_id='" + match_id + "'"))
        if len(confirmation["payload"]["results"]) != 2 or not outcomes or outcomes[0]["payload"]["status"] != "CONFIRMED":
            raise AssertionError("F1 confirmation does not represent both VERSUS participants")
        if db_scalar("SELECT count(*) FROM users WHERE external_id='h04-versus-winner' AND experience=100") != 1 or db_scalar(
                "SELECT count(*) FROM users WHERE external_id='h04-versus-loser' AND experience=0") != 1:
            raise AssertionError("F1 participant XP does not match submitted deltas")
        score_snapshot = (float(client.zscore("ranking:global", "h04-versus-winner") or 0),
                          float(client.zscore("ranking:global", "h04-versus-loser") or 0),
                          client.llen("user:h04-versus-winner:history"), client.llen("user:h04-versus-loser:history"))
        if score_snapshot != (100.0, 0.0, 1, 1):
            raise AssertionError("F1 Redis ranking/history were not applied exactly once")
        duplicate = json.dumps(confirmation).encode()
        from aiokafka import AIOKafkaProducer
        duplicate_producer = AIOKafkaProducer(bootstrap_servers=BOOTSTRAP, acks="all")
        await duplicate_producer.start()
        try:
            await duplicate_producer.send_and_wait("matches.saga.confirmed.v1", key=match_id.encode(), value=duplicate)
        finally:
            await duplicate_producer.stop()
        await asyncio.sleep(2)
        if score_snapshot != (float(client.zscore("ranking:global", "h04-versus-winner") or 0),
                              float(client.zscore("ranking:global", "h04-versus-loser") or 0),
                              client.llen("user:h04-versus-winner:history"), client.llen("user:h04-versus-loser:history")):
            raise AssertionError("F1 duplicate confirmation changed Redis effects")
        await audit.stop()
        print("PASS F1 VERSUS two results, atomic confirmation and Redis idempotency")

    if selected("F2") or selected("F"):
        match_id = "h04-versus-fail-" + uuid.uuid4().hex[:8]
        audit = await start_audit_consumer()
        sql("INSERT INTO users (external_id,language,tutorial_completed,experience,streak,created_at) "
            "VALUES ('h04-versus-conflict','es',false,0,0,CURRENT_TIMESTAMP); "
            "INSERT INTO user_results (user_id,case_id,mode,won,time_seconds,xp_delta,created_at,match_id) "
            "SELECT id,'different-case','VERSUS',false,42,0,CURRENT_TIMESTAMP,'" + match_id + "' "
            "FROM users WHERE external_id='h04-versus-conflict'")
        complete_from_event(repository, match_id, "VERSUS", [
            {"userId": "h04-versus-first", "won": True, "xpDelta": 100, "difficulty": None},
            {"userId": "h04-versus-conflict", "won": False, "xpDelta": 0, "difficulty": None},
        ])
        await wait_until(lambda: repository.get_match(match_id).get("sagaStatus") == "CANCELLED", "F2 second participant failure cancellation", 70)
        outcomes = await audited_outcomes(audit, match_id)
        await audit.stop()
        if not outcomes or any(item["payload"]["status"] == "CONFIRMED" for item in outcomes) or not all(
                item["payload"]["status"] == "CANCELLED" for item in outcomes):
            raise AssertionError("F2 Kafka audit did not show cancellation only")
        if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + match_id + "'") != 1:
            raise AssertionError("F2 transaction left a partial terminal result")
        if db_scalar("SELECT count(*) FROM user_results WHERE match_id='" + match_id + "' AND user_id=(SELECT id FROM users WHERE external_id='h04-versus-first')") != 0:
            raise AssertionError("F2 first participant result escaped transaction rollback")
        if db_scalar("SELECT count(*) FROM match_saga_outbox WHERE match_id='" + match_id + "'") != 0:
            raise AssertionError("F2 failed transaction left a confirmation outbox row")
        if db_scalar("SELECT count(*) FROM users WHERE external_id='h04-versus-first' AND experience=0") != 1:
            raise AssertionError("F2 first participant retained partial XP")
        print("PASS F2 second participant failure rolls back first result and XP; no false confirmation")

    await runtime.stop()
    RUNTIME = None
    stop_users(users)
    client.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    finally:
        if RUNTIME is not None:
            try:
                asyncio.run(RUNTIME.stop())
            except Exception:
                pass
        for process in PROCESSES:
            stop_users(process)
