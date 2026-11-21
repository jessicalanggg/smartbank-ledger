package com.smartbank.transaction;
import java.util.List;
import java.util.UUID;
public record LinkedTransferResponse(UUID linkId, List<TransactionResponse> transfers) { }
