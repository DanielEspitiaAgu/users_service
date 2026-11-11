package co.edu.uptc.users_service.exception;

public class ResultConflictException extends RuntimeException {

    public ResultConflictException() {
        super("El matchId ya fue registrado con datos diferentes");
    }
}
