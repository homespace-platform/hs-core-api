package com.hs.contract.dto.billing;

import java.time.LocalDate;

public record CreateOverdueActionRequest(OverdueAction.Type type, String note,
                                         LocalDate proposedDate) {}
