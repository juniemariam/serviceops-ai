package com.junie.serviceops.api;
import org.springframework.http.HttpStatus; import org.springframework.web.bind.annotation.*; import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler { @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.NOT_FOUND) Map<String,String> notFound(IllegalArgumentException e){return Map.of("error",e.getMessage());} }
