package com.scx.review;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/records")
public class RecordController {
    private final RecordService service;
    public RecordController(RecordService service) { this.service = service; }
    public record LeaseBody(@NotBlank String lockToken) {}
    public record ReviewBody(@NotBlank String lockToken, @Pattern(regexp="NORMAL|ATTACK") @NotNull String reviewedLabel,
            @NotNull @Size(max=1000) String note) {}
    @GetMapping public Map<String, Object> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="") String label,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="score") String order) {
        return service.list(page, size, label, status, order);
    }
    @GetMapping("/stats") public Map<String, Object> stats() { return service.stats(); }
    @GetMapping("/{id}") public Map<String, Object> detail(@PathVariable long id) { return service.detail(id); }
    @PostMapping("/{id}/lock") public Map<String, Object> lock(@PathVariable long id, Principal p) { return service.acquire(id, p.getName()); }
    @PostMapping("/{id}/unlock") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable long id, @Valid @RequestBody LeaseBody body, Principal p) { service.release(id, p.getName(), body.lockToken()); }
    @PatchMapping("/{id}") public Map<String, Object> save(@PathVariable long id, @Valid @RequestBody ReviewBody body, Principal p) {
        return service.save(id, p.getName(), body.lockToken(), body.reviewedLabel(), body.note());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @Valid @RequestBody LeaseBody body, Principal p) { service.delete(id, p.getName(), body.lockToken()); }
}
