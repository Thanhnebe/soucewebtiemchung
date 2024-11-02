package com.web.dto;

import com.web.enums.PayType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PaymentRequest {

    private String orderId;

    private String requestId;

    private String vnpOrderInfo;

    private String vnpayUrl;

    private PayType payType;
}
