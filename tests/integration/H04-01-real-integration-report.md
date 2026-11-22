# H04-01 — informe de integración real

Fecha: 2026-11-21 (America/Bogota).

## A. Estado inicial

- `users_service`: `fix/h04-users-transactional-outbox`, HEAD `c044f8f1233e897973d30e1151b2d1bf5a6ad725`.
- `partidas-service`: `main`, HEAD `0dca48edd8dabf7774146d4c7a19f9d59c819274`.
- No se cambió de rama, se descartó trabajo, se hizo fetch, commit, push ni PR.

## B. Entorno y aislamiento

Cada ejecución del runner [run_h04_real_saga.ps1](../run_h04_real_saga.ps1) crea un proyecto Compose aleatorio con su propia red, Kafka `apache/kafka:4.4.0`, PostgreSQL `16.15-alpine` y Redis `7-alpine`. No usa volúmenes, publica puertos de datos solo en loopback y crea topics y grupos de consumidores exclusivos. PostgreSQL recibe una base nueva bajo el perfil `flyway-fresh`; Kafka, PostgreSQL y Redis se verifican antes del inicio.

Usuarios se ejecutó como proceso Spring real contra esos endpoints. Partidas utilizó su `MatchSagaRuntime`, `MatchRepository`, `FinishTimedMatch` y builder de eventos reales; no se sustituyeron Kafka, PostgreSQL ni Redis por mocks. Se usan UUID en cada `matchId`, `eventId`, `correlationId` y grupo de auditoría. La auditoría Kafka empieza en `latest` con grupo único; las pruebas operan sobre PostgreSQL y Redis vacíos de cada ejecución.

## C. Escenarios

| Escenario | Resultado | Evidencia observada |
|---|---|---|
| A — flujo normal | **APROBADO** | En la suite integrada: resultado final desde Partidas, una fila `user_results`, outbox `PUBLISHED`, outcome `CONFIRMED` y ranking Redis igual al XP terminal (250). |
| B — Kafka indisponible | **APROBADO** | Ejecutado solo y dentro de la suite. Se observó una fila `PENDING` con `attempts=0`; Kafka se detuvo y su estado no-running se verificó. Mientras estaba detenido, `status`, `attempts` y `updated_at` se mantuvieron iguales. Tras restaurar el broker y reiniciar Usuarios, hubo una fila de resultado, outbox `PUBLISHED` y saga `CONFIRMED`. |
| C — caída entre publish y ack | **APROBADO** | El broker aceptó `CONFIRMED`; un trigger PostgreSQL temporal retrasó el ack. Tras comprobar `PgSleep`, se terminó el backend PostgreSQL de ese ack, el lease expiró y el publicador volvió a enviar. `attempts >= 2`, outbox `PUBLISHED`, ranking e historial Redis sin efecto duplicado. |
| D — fallo durante commit PostgreSQL | **APROBADO** | Ejecutado independientemente y en suite. Un constraint trigger `DEFERRABLE INITIALLY DEFERRED` falló dentro de PostgreSQL al cerrar la transacción del listener. No quedaron resultados terminales, recompensa, ni fila outbox; la cuenta del perfil que `REQUIRES_NEW` crea quedó con experiencia 0. El consumidor de auditoría vio solo `CANCELLED`, nunca `CONFIRMED`; Redis no confirmó la saga. |
| E — reinicio de Usuarios | **APROBADO** | La confirmación permaneció pendiente con Usuarios detenido. Tras reiniciarlo, se publicó y Partidas terminó en `CONFIRMED`; el replay del outcome no alteró el historial Redis. |
| F1 — VERSUS exitoso | **APROBADO** | Ejecutado solo y junto con F2, y en la suite. Dos filas PostgreSQL, una confirmación `PUBLISHED` cuyo payload contiene ambos resultados, y outcome `CONFIRMED`. XP de participantes 100/0; ranking 100/0 e historial 1/1. Se republicó la misma confirmación y Redis no cambió. |
| F2 — falla al registrar el segundo participante | **APROBADO** | Un resultado preexistente contradictorio para el segundo jugador provocó el error real de dominio dentro del listener transaccional. Solo permaneció esa fila de referencia; no quedaron resultados terminales del evento ni outbox. La experiencia del primer perfil quedó en 0. Kafka publicó solo `CANCELLED`; Partidas no confirmó la saga. |

**Suite integrada:** B, E, A, C, D, F1 y F2 completaron todas sus aserciones en una misma ejecución limpia. **Pruebas independientes:** B, D, F1, F2 y F combinado (F1 seguido de F2) terminaron correctamente.

## D. Causa y correcciones de la inestabilidad B

La corrida inconclusa anterior consultaba `PENDING` y después detenía el broker mientras un publicador podía completar el envío y el ack; la fila ya aparecía `PUBLISHED` al hacer la segunda consulta. El evento era nuevo y el proyecto/DB/topic/grupos eran exclusivos, así que no fue contaminación residual. La evidencia de `attempts=1` indica que el envío había avanzado; la prueba original no imponía una barrera antes de publicar.

Se movió B al inicio de la suite integrada, antes de iniciar otros flujos; el servicio de esa fase recibe el retardo de publicación como argumento CLI explícito de Spring por 24 horas. La prueba verifica que la fila esté aún `PENDING`, captura `attempts` y `updated_at`, detiene Kafka y verifica que está parado, compara la fila mientras Kafka no está disponible y recién después restaura el broker. Al reiniciar Usuarios con retardo corto se observa el reintento y la confirmación. La barra de inicio del consumidor se basa en el log de suscripción del listener Spring; se eliminó el check externo `kafka-consumer-groups --describe`, que podía agotar su timeout antes de ejecutar el escenario.

Un intento de suite anterior ordenaba B detrás de E/A y falló en su aserción; no se contabilizó como aprobado. Con B aislado al inicio y la barrera nueva, B pasó de forma independiente y en la ejecución integrada.

## E. Compatibilidad y consistencia

- D produjo un outcome Kafka `CANCELLED` para que Partidas no quedara pendiente; no se observó ningún `CONFIRMED` asociado al commit fallido.
- F1 produjo un solo outcome coherente con los dos participantes. La entrega duplicada se descartó por `sagaStatus` en el consumidor real de Partidas, sin repetir XP, ranking ni historial.
- F2 y D demostraron que errores previos al commit no dejan confirmación de éxito. El DLT real de Usuarios emitió cancelación.
- No se alteraron topics ni esquemas de eventos. Los escenarios usaron `matches.finished.v1`, `matches.finished.dlq` y `matches.saga.confirmed.v1` existentes.
- Las excepciones controladas viven solo como triggers en la base temporal y se eliminan al terminar; no se añadió instrumentación de fallo a producción.

## F. Regresión

- Runner PostgreSQL aislado de Usuarios: **127 pruebas, 0 fallos, 0 errores, 2 omitidas; BUILD SUCCESS**. Flyway aplicó y validó V1–V4 sobre esquemas nuevos; Hibernate validó el resultado.
- La cobertura CLASSIC PostgreSQL conserva los valores EASY/MEDIUM/HARD de 50/100/150 XP y sus recompensas idempotentes; está incluida en la suite de 127.
- Partidas: **19 aprobadas** (`test_match_saga_repository.py`, `test_timed_use_cases.py`).
- Sintaxis del harness Python y del runner PowerShell: válida. `git diff --check`: aprobado.
- La suite integrada tuvo logs intermitentes de heartbeat/rebalance/commit de `aiokafka` durante paradas y reinicios del broker. Los consumidores se recuperaron; las aserciones de estado, PostgreSQL y Redis terminaron aprobadas. Queda como observación de ejecución de Partidas, sin corrección fuera del alcance.

## G. Archivos

En esta fase de validación se modificaron:

- `tests/integration/h04_real_saga_harness.py`: selectores individuales, auditoría de outcomes, barreras y asserts B/D/F, timestamps/attempts.
- `tests/run_h04_real_saga.ps1`: preservación/restauración del selector `H04_SCENARIOS`.
- `tests/integration/H04-01-real-integration-report.md`: este informe.

También permanecen sin commit los cambios H04-01 previos, incluida V4, el listener/outbox y sus pruebas. No se modificaron archivos de `partidas-service`.

## H. Limpieza y seguridad

El runner eliminó únicamente cada proyecto temporal y su red, sin `-v`. Al cierre no quedaron contenedores con la etiqueta `com.deathdoku.test=h04-real-saga`, procesos temporales de Java/Python ni cambios en contenedores compartidos. Los mismos contenedores compartidos continuaron saludables. No se usaron bases, broker, Redis o credenciales del equipo; las credenciales temporales se generaron en variables de proceso y no se imprimieron.

## I. Estado Git

- `users_service` sigue en `fix/h04-users-transactional-outbox`; los cambios existentes se conservaron y siguen sin commit.
- `partidas-service` sigue en `main`, limpio.
- Sin commit, push ni PR.

## J. Recomendación

**APROBAR CON OBSERVACIONES.** A–F y E pasaron individualmente donde corresponde y en la suite integrada; D y F ya tienen evidencia de PostgreSQL, Kafka y Redis reales aislados. La observación no bloqueante es la actividad intermitente de heartbeat/rebalance de `aiokafka` durante reinicios de broker; no impidió recuperar las sagas ni alteró los efectos idempotentes. H04-01 queda listo para revisión humana y preparación de PR; esta ejecución no creó el PR.
