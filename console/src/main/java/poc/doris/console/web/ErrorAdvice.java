package poc.doris.console.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Form posts that break a rule go back to the page they came from, with the reason shown on top. */
@ControllerAdvice
class ErrorAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    String rejected(ResponseStatusException e, HttpServletRequest request, RedirectAttributes flash) {
        flash.addFlashAttribute("error", e.getStatusCode().value() + " · " + e.getReason());
        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null ? referer : "/");
    }
}
