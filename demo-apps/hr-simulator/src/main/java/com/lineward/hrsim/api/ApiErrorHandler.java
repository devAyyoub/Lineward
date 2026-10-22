/*
 * Copyright 2026 Ayyoub Amjahed Abed
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lineward.hrsim.api;

import com.lineward.hrsim.model.InvalidRequestException;
import com.lineward.hrsim.model.UnknownEmployeeException;


import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the two domain failures into HTTP status codes.
 *
 * <p>Status codes and not a 200 with an error body, because the connector on the
 * other side has to be able to tell "this employee is gone" from "the request was
 * wrong" without parsing prose. {@link ProblemDetail} is RFC 9457, which is what
 * Spring produces for errors by default anyway.
 */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(UnknownEmployeeException.class)
    ProblemDetail onUnknownEmployee(UnknownEmployeeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail onInvalidRequest(InvalidRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
