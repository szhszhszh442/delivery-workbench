package io.projectforjob.businessapi.directory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.projectforjob.businessapi.api.ApiFailure;
import io.projectforjob.businessapi.api.ApiResponse;
import io.projectforjob.businessapi.api.InsertId;

@Service
public class DirectoryCommands {

    private final CommandMapper commands;
    private final DirectoryMapper directory;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    DirectoryCommands(CommandMapper commands, DirectoryMapper directory, ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.commands = commands;
        this.directory = directory;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> Reply execute(long userId, String key, String action, List<?> fields,
            Supplier<T> readScope, Function<T, Saved> command) {
        validateKey(key);
        var hash = hash(fields);
        try {
            return transaction.execute(status -> {
                var scope = readScope.get();
                var existing = commands.find(userId, key);
                if (existing != null) {
                    return replay(userId, action, hash, existing);
                }
                var saved = command.apply(scope);
                var result = json.valueToTree(saved.data());
                var receiptId = new InsertId();
                try {
                    commands.insert(userId, key, saved.workspaceId(), saved.projectId(), action, hash,
                            saved.httpStatus(), json.writeValueAsString(result), receiptId);
                } catch (DuplicateKeyException cause) {
                    throw new ReceiptCollision(cause);
                }
                if (saved.recordActivity() != null) {
                    saved.recordActivity().accept(receiptId.getId());
                }
                return new Reply(saved.httpStatus(), result, false);
            });
        } catch (ReceiptCollision collision) {
            return transaction.execute(status -> {
                readScope.get();
                var existing = commands.find(userId, key);
                if (existing == null) {
                    throw collision;
                }
                return replay(userId, action, hash, existing);
            });
        }
    }

    ReceiptView receipt(long userId, String key) {
        validateKey(key);
        var receipt = commands.find(userId, key);
        if (receipt == null) {
            throw ApiFailure.notFound();
        }
        requireReceiptAccess(userId, receipt);
        return new ReceiptView(receipt.action(), receipt.httpStatus(), json.readTree(receipt.result()));
    }

    private Reply replay(long userId, String action, String hash, CommandMapper.Receipt receipt) {
        if (!action.equals(receipt.action()) || !hash.equals(receipt.requestHash())) {
            throw ApiFailure.keyReused();
        }
        requireReceiptAccess(userId, receipt);
        return new Reply(receipt.httpStatus(), json.readTree(receipt.result()), true);
    }

    private void requireReceiptAccess(long userId, CommandMapper.Receipt receipt) {
        if (receipt.projectId() != null) {
            if (directory.project(userId, receipt.projectId()) == null) {
                throw ApiFailure.notFound();
            }
        } else if (receipt.workspaceId() != null && directory.workspace(userId, receipt.workspaceId()) == null) {
            throw ApiFailure.notFound();
        }
    }

    private String hash(List<?> fields) {
        try {
            var bytes = json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[!-~]{1,64}")) {
            throw ApiFailure.invalid();
        }
    }

    public record Saved(Long workspaceId, Long projectId, int httpStatus, Object data, LongConsumer recordActivity) {
        Saved(Long workspaceId, Long projectId, int httpStatus, Object data) {
            this(workspaceId, projectId, httpStatus, data, null);
        }
    }

    public record Reply(int httpStatus, JsonNode data, boolean replayed) {
        public ResponseEntity<?> response() {
            var envelope = ApiResponse.success(data);
            return ResponseEntity.status(httpStatus).body(replayed
                    ? new ApiResponse.Replay<>(data, envelope.requestId(), true)
                    : envelope);
        }
    }

    record ReceiptView(String action, int httpStatus, JsonNode result) {
    }

    private static class ReceiptCollision extends RuntimeException {
        ReceiptCollision(DuplicateKeyException cause) {
            super(cause);
        }
    }
}
