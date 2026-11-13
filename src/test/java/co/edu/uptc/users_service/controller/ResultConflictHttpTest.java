package co.edu.uptc.users_service.controller;

import co.edu.uptc.users_service.exception.GlobalExceptionHandler;
import co.edu.uptc.users_service.exception.ResultConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultConflictHttpTest {

    @Test
    void contradictoryMatchIdIsMappedToHttp409() {
        assertEquals(HttpStatus.CONFLICT,
                new GlobalExceptionHandler().handleResultConflict(new ResultConflictException()).getStatusCode());
    }
}
