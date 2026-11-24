package cn.linke;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/api", "/api/v1"})
class VerificationController {
    private final VerificationService service;
    VerificationController(VerificationService service) { this.service = service; }

    @GetMapping("/verifications")
    List<VerificationService.Entry> mine(HttpServletRequest request) {
        return service.mine(AuthController.session(request));
    }
    @PostMapping(value="/verifications", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    VerificationService.Entry submit(HttpServletRequest request, @RequestParam(name="verify_type", required=false) String type,
                                     @RequestParam(required=false) Boolean policiesAccepted,
                                     @RequestParam(required=false) MultipartFile file) {
        return service.submit(AuthController.session(request), type, file, policiesAccepted);
    }
    @GetMapping("/admin/verifications")
    List<VerificationService.Entry> queue(HttpServletRequest request, @RequestParam(defaultValue="pending") String status) {
        return service.queue(AuthController.session(request), status);
    }
    @PostMapping("/admin/verifications/{id}/review")
    VerificationService.Entry review(HttpServletRequest request, @PathVariable UUID id, @RequestBody VerificationService.Decision decision) {
        return service.review(AuthController.session(request), id, decision);
    }
    @DeleteMapping("/admin/verifications/{id}/proof")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeProof(HttpServletRequest request, @PathVariable UUID id) {
        service.requestDeletion(AuthController.session(request), id);
        service.purgeProofs();
    }
    @GetMapping({"/verifications/{id}/proof", "/admin/verifications/{id}/proof"})
    ResponseEntity<byte[]> proof(HttpServletRequest request, @PathVariable UUID id) {
        var proof = service.proof(AuthController.session(request), id);
        var type = switch (proof.extension()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "jpg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> throw new IllegalStateException();
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("proof." + proof.extension()).build().toString())
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .body(proof.bytes());
    }
}
