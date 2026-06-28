package com.ofertagames.backend.api;

import com.ofertagames.backend.source.itad.ItadSyncService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final ItadSyncService syncService;
    private final String syncSecretKey;

    public SyncController(ItadSyncService syncService,
                          @Value("${sync.secret.key}") String syncSecretKey) {
        this.syncService = syncService;
        this.syncSecretKey = syncSecretKey;
    }

    @PostMapping
    public ResponseEntity<String> sync(@RequestHeader("X-Sync-Key") String key) {
        if (!syncSecretKey.equals(key)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        syncService.sync();
        return ResponseEntity.ok("Sync complete");
    }
}
