package com.cb.agent.api.http;

import com.cb.agent.app.document.UploadLimits;
import com.cb.agent.app.extract.QueueFullException;
import com.cb.agent.app.extract.TaskConflictException;
import com.cb.agent.app.extract.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把出错的情况回成统一的样子：
 * <pre>
 *   {"error": {"code": "bad_request", "message": "skill 的名称不对：…"}}
 * </pre>
 * {@code code} 给程序判断用，{@code message} 给人看。
 *
 * <table>
 *   <caption>状态码</caption>
 *   <tr><td>400 bad_request</td><td>请求写错了：少了东西、有不认识的键、skill 不存在、文件读不了、参数不在范围里</td></tr>
 *   <tr><td>404 not_found</td><td>没有这个任务、任务里没有这份文档、没有这个地址</td></tr>
 *   <tr><td>409 conflict</td><td>和任务现在的状况冲突：幂等键用在了不同的内容上、任务没结束就删、文档没有结果</td></tr>
 *   <tr><td>413 too_large</td><td>文件或请求太大</td></tr>
 *   <tr><td>503 queue_full</td><td>排队的文档太多，过一会儿再提交</td></tr>
 *   <tr><td>500 internal</td><td>服务自己出了错。细节在服务的日志里，不回给调用方</td></tr>
 * </table>
 * 另外 401（令牌不对）、411（请求没有说明长度）由 {@link ApiGuardFilter} 在进到这里之前就回了，样子相同。
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 出错时的回应体。 */
    static Map<String, Object> body(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        return out;
    }

    private static ResponseEntity<Object> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }

    @ExceptionHandler(UploadLimits.TooLargeException.class)
    ResponseEntity<Object> tooLarge(UploadLimits.TooLargeException e) {
        return respond(HttpStatus.CONTENT_TOO_LARGE, "too_large", e.getMessage());
    }

    /** 读取器、skill、字段定义、请求的检查抛出的都是这一种，消息已经是写给人看的。 */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> badRequest(IllegalArgumentException e) {
        return respond(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<Object> notFound(TaskNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(TaskConflictException.class)
    ResponseEntity<Object> conflict(TaskConflictException e) {
        return respond(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler(QueueFullException.class)
    ResponseEntity<Object> queueFull(QueueFullException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(body("queue_full", e.getMessage()));
    }

    /** multipart 表单本身有问题（格式不对、文件个数超过了容器的上限）。 */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Object> multipart(MultipartException e) {
        if (e instanceof MaxUploadSizeExceededException) {
            return respond(HttpStatus.CONTENT_TOO_LARGE, "too_large",
                    "上传的文件太大。上限见配置 agent.api.max-file-bytes 和 agent.api.max-request-bytes");
        }
        return respond(HttpStatus.BAD_REQUEST, "bad_request", "上传的表单读不了：格式不对，或者文件个数太多");
    }

    /** 没有料到的错误。细节记在日志里，不回给调用方：异常的消息里可能带着内部的路径。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> internal(Exception e) {
        log.error("处理请求时出了意料之外的错误", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "服务内部出错了，细节在服务的日志里");
    }

    /**
     * Spring 自己发现的问题（请求体不是 JSON、Content-Type 不对、地址不存在…）都会走到这里。
     * 默认的回应是另一种格式的英文说明，这里换成和上面一样的样子。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object ignored, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        int value = status.value();
        String code = switch (value) {
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 413 -> "too_large";
            case 415 -> "unsupported_media_type";
            default -> value >= 500 ? "internal" : "bad_request";
        };
        if (value >= 500) {
            log.error("处理请求时出错", e);
        }
        return new ResponseEntity<>(body(code, message(e, value)), headers, status);
    }

    private static String message(Exception e, int status) {
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体读不了：不是合法的 JSON，或者是空的";
        }
        if (e instanceof HttpMediaTypeNotSupportedException) {
            return "Content-Type 要是 application/json 或者 multipart/form-data";
        }
        if (e instanceof HttpRequestMethodNotSupportedException wrong) {
            return "这个地址不支持 " + wrong.getMethod();
        }
        if (e instanceof MissingServletRequestParameterException missing) {
            return "少了参数 " + missing.getParameterName();
        }
        if (e instanceof TypeMismatchException mismatch) {
            return "参数的值不对：" + mismatch.getValue();
        }
        if (e instanceof MaxUploadSizeExceededException) {
            return "上传的文件太大。上限见配置 agent.api.max-file-bytes 和 agent.api.max-request-bytes";
        }
        return switch (status) {
            case 404 -> "没有这个地址";
            case 413 -> "请求太大";
            default -> status >= 500 ? "服务内部出错了，细节在服务的日志里" : "请求有问题";
        };
    }
}
