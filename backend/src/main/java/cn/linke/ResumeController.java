package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ResumeController {
    private final ResumeService resumes;
    ResumeController(ResumeService resumes) { this.resumes = resumes; }

    @PostMapping(value={"/api/upload/resume", "/api/v1/upload/resume"}, consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResumeService.Attachment upload(HttpServletRequest request, @RequestParam(required=false) MultipartFile file,
                                    @RequestParam(required=false) Long expectedVersion) {
        return resumes.upload(AuthController.session(request), expectedVersion, file);
    }
    @DeleteMapping({"/api/upload/resume", "/api/v1/upload/resume"})
    ResumeService.Attachment delete(HttpServletRequest request, @RequestParam(required=false) Long expectedVersion) {
        return resumes.delete(AuthController.session(request), expectedVersion);
    }
    @GetMapping(value="/api/v1/resumes/{id}.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> read(HttpServletRequest request, @PathVariable UUID id, @RequestParam(defaultValue="false") boolean download) {
        var file = resumes.read(AuthController.session(request), id);
        var disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .header("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            .body(file.content());
    }
}
