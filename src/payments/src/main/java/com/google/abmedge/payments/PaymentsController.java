// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.abmedge.payments;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.PostConstruct;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.google.abmedge.payment.Payment;
import com.google.abmedge.payment.PaymentUnit;
import com.google.abmedge.payments.dao.DatabasePaymentGateway;
import com.google.abmedge.payments.dao.PaymentGateway;
import com.google.abmedge.payments.dto.Bill;
import com.google.abmedge.payments.dto.PaymentStatus;
import com.google.abmedge.payments.util.BillGenerator;
import com.google.abmedge.payments.util.PaymentProcessingFailedException;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.apache.commons.lang3.StringUtils;

/**
 * This is the main controller class for the payments service defines the APIs exposed by the
 * service. The controller also defines 2 APIs (/ready and /healthy) for readiness and health
 * checkups
 */
@RestController
public class PaymentsController {

  private static final Logger LOGGER = LogManager.getLogger(PaymentsController.class);
  private static final Gson GSON = new Gson();
  private PaymentGateway activePaymentGateway;
  private final DatabasePaymentGateway databasePaymentGateway;
  private static final String LLM_EP_ENV = "LLM_EP";
  private static final String LLM_EP_CHAT = "/chat";
  private static final String LLM_EP_UPSELL= "/upsell";
  private static  String LLM_SERVICE = "http://localhost:8888";
  // private static  String LLM_SERVICE = "http://next-action-agent-svc.next-action-assistant:80";
  private static final HttpClient HTTP_CLIENT =
  HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(11))
      .build();


  public PaymentsController(DatabasePaymentGateway databasePaymentGateway) {
    this.databasePaymentGateway = databasePaymentGateway;
  }

  /**
   * This method runs soon after the object for this class is created on startup of the application.
   */
  @PostConstruct
  void init() {
    activePaymentGateway = databasePaymentGateway;
  }

  public static void initServiceEndpoints() {
    String llm = System.getenv(LLM_EP_ENV);
    if (StringUtils.isNotBlank(llm)) {
      LOGGER.info(String.format("Setting llm service endpoint to: %s", llm));
      LLM_SERVICE = llm;
    } else {
      LOGGER.warn(
          String.format(
              "Could not read environment variable %s; thus defaulting to %s",
              LLM_EP_ENV, LLM_SERVICE));
    }
  }

  @RequestMapping("/")
  public String home() {
    return "Hello Anthos BareMetal - Payments Controller";
  }

  /**
   * Readiness probe endpoint.
   *
   * @return HTTP Status 200 if server is ready to receive requests.
   */
  @GetMapping("/ready")
  @ResponseStatus(HttpStatus.OK)
  public String readiness() {
    return "ok";
  }

  /**
   * Liveness probe endpoint.
   *
   * @return HTTP Status 200 if server is healthy and serving requests.
   */
  @GetMapping("/healthy")
  public ResponseEntity<String> liveness() {
    // TODO:: Add suitable liveness check
    return new ResponseEntity<>("ok", HttpStatus.OK);
  }

  /**
   * This method binds the controller to the '/pay' API requests. The method takes in an argument of
   * {@link Payment} and uses the currently active payment gateway's ({@link
   * PaymentsController#activePaymentGateway}) implementation to process the payment. As a response
   * to this request the API returns a bill with all the details.
   *
   * @param payment an object of type {@link Payment} containing details of all the items purchased
   *     in this payment, the amount paid and the type of payment
   * @return a bill for the payment that was processed in string format
   */
  
  @PostMapping(value = "/pay", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> pay(@RequestBody Payment payment) {
    String jsonString;
    try {
      Bill bill = this.activePaymentGateway.pay(payment);
      jsonString = GSON.toJson(bill, Bill.class);
    } catch (PaymentProcessingFailedException ex) {
      String msg =
          String.format(
              "Failed to process payment id '%s' with amount $%s",
              payment.getId(), payment.getPaidAmount());
      LOGGER.error(msg, ex);
      return new ResponseEntity<>(msg, HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return new ResponseEntity<>(jsonString, HttpStatus.OK);
  }

  @PostMapping(value = "/upsell", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> upsell(@RequestBody Payment payment) {
    PaymentsController.initServiceEndpoints();

    String jsonString;
    List<String> itemlist = new ArrayList<>();

    for (PaymentUnit pu : payment.getUnitList()) {
      itemlist.add(pu.getName());
    }

    Gson gson = new Gson();

    // Create a Map to represent the JSON structure
    Map<String, Object> requestBodyMap = new HashMap<>();
    String message = "With the items:" + itemlist.toString() + ", what recipes can I make?";
    requestBodyMap.put("message", message);
    requestBodyMap.put("item_flag", false);
    requestBodyMap.put("upsell_flag", true);
    
    // String encodedMessage = UriUtils.encodeQueryParam(message, StandardCharsets.UTF_8);
    String requestBody = gson.toJson(requestBodyMap);
    LOGGER.info(message);
    String endpoint = LLM_SERVICE + LLM_EP_UPSELL;
    LOGGER.info(String.format(endpoint));
    String content = new String();
    try {
    HttpRequest request =
        HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .build();
    HttpResponse<String> response =
            HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        if (statusCode == HttpStatus.OK.value() || statusCode == HttpStatus.NO_CONTENT.value()) {
          String responseUpsellItems = response.body();
          Gson gson_response = new Gson();
          JsonObject jsonUpsellItems = gson_response.fromJson(responseUpsellItems,JsonObject.class);
          content = jsonUpsellItems.get("content").getAsString();

          LOGGER.info(String.format(
                  "'%s' Response: \n%s",
                  endpoint, content));

        }
      }     
        catch (IOException | InterruptedException e) {
        LOGGER.error(String.format("Failed to fetch recipes '%s'", endpoint), e);
      }

    // try {
    //   Bill bill = this.activePaymentGateway.pay(payment);
    //   jsonString = GSON.toJson(bill, Bill.class);
    // } catch (PaymentProcessingFailedException ex) {
    //   String msg =
    //       String.format(
    //           "Failed to process payment id '%s' with amount $%s",
    //           payment.getId(), payment.getPaidAmount());
    //   LOGGER.error(msg, ex);
    //   return new ResponseEntity<>(msg, HttpStatus.INTERNAL_SERVER_ERROR);
    // }
    return new ResponseEntity<>(content, HttpStatus.OK);
  }


}
