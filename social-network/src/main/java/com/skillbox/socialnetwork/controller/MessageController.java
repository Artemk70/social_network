package com.skillbox.socialnetwork.controller;

import com.skillbox.socialnetwork.api.request.MessageRequest;
import com.skillbox.socialnetwork.api.response.AccountResponse;
import com.skillbox.socialnetwork.api.response.DataResponse;
import com.skillbox.socialnetwork.api.response.ListResponse;
import com.skillbox.socialnetwork.api.response.dialogdto.MessageData;
import com.skillbox.socialnetwork.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
@Tag(name = "Контроллер сообщений")
public class MessageController {
    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/dialogs/{id}/messages")
    @PreAuthorize("hasAuthority('user:write')")
    @Operation(summary = "Получить сообщения", security = @SecurityRequirement(name = "jwt"))
    public ResponseEntity<ListResponse<MessageData>> getMessages(@PathVariable int id,
                                                                 @RequestParam(name = "query", defaultValue = "") String query,
                                                                 @RequestParam(name = "offset", defaultValue = "0") int offset,
                                                                 @RequestParam(name = "itemPerPage", defaultValue = "1000") int itemPerPage,
                                                                 @RequestParam(name = "fromMessageId", defaultValue = "0") int fromMessageId,
                                                                 Principal principal) {
        return new ResponseEntity<>(messageService.getMessages(id, offset, itemPerPage, principal), HttpStatus.OK);
    }

    @PostMapping("/dialogs/{id}/messages")
    @PreAuthorize("hasAuthority('user:write')")
    @Operation(summary = "Отправить сообщение", security = @SecurityRequirement(name = "jwt"))
    public ResponseEntity<DataResponse<MessageData>> postMessages(@PathVariable int id,
                                                                  @RequestBody MessageRequest messageRequest,
                                                                  Principal principal) {
        return new ResponseEntity<>(messageService.postMessage(id, messageRequest, principal), HttpStatus.OK);
    }

    @GetMapping("/dialogs/unreaded")
    @PreAuthorize("hasAuthority('user:write')")
    @Operation(summary = "Получить непрочитанные", security = @SecurityRequirement(name = "jwt"))
    public ResponseEntity<AccountResponse> getUnread(Principal principal) {
        return new ResponseEntity<>(messageService.getUnread(principal), HttpStatus.OK);
    }
}
