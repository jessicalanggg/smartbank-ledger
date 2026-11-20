package com.smartbank.common;

import com.smartbank.account.AccountNotFoundException;
import com.smartbank.auth.DuplicateEmailException;
import org.springframework.security.authentication.BadCredentialsException;
import com.smartbank.transaction.BankingException;
import com.smartbank.transaction.HoldNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail duplicateEmail(DuplicateEmailException ex) {
        return problem(HttpStatus.CONFLICT, "Registration rejected", "Email is already registered");
    }
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail badCredentials(BadCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid email or password");
    }
    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail notFound(AccountNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Account not found", ex.getMessage());
    }
    @ExceptionHandler(HoldNotFoundException.class)
    public ProblemDetail holdNotFound(HoldNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Hold not found", ex.getMessage());
    }
    @ExceptionHandler(BankingException.class)
    public ProblemDetail banking(BankingException ex) {
        return problem(HttpStatus.CONFLICT, "Banking operation rejected", ex.getMessage());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Check required fields and monetary amount (positive, at most 2 decimal places).");
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent or duplicate operation", "Operation conflicts with a database constraint; retry using the same request key if applicable.");
    }
    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
