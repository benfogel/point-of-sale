/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.abmedge.payments.util;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

import com.google.abmedge.payment.Payment;
import com.google.abmedge.payment.PaymentType;
import com.google.abmedge.payment.PaymentUnit;
import com.google.abmedge.payments.dto.Bill;
import com.google.abmedge.payments.dto.PaymentStatus;
import com.google.gson.Gson;
import com.google.gson.JsonObject;


public class BillGenerator {

  private static final Logger LOGGER = LogManager.getLogger(BillGenerator.class);
  private static final String BILL_HEADER =
      "----------------------------------------------------------------------------\n";
  private static final String SPACE = " ";
  private static final String TOTAL = "  Total:";
  private static final String TAX = "  Tax:";
  private static final String PAID = "  Paid:";
  private static final String BALANCE = "  Balance:";
  private static final double TAX_VALUE = 0.1495;
  private static final HttpClient HTTP_CLIENT =
  HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(11))
      .build();
  private static final String LLM_EP_ENV = "LLM_EP";
  private static final String LLM_EP = "/chat";
  private static  String LLM_SERVICE = "http://localhost:8888";

  

  /**
   * This method takes in an ID identifying a payment and a {@link Payment} object and generates a
   * string representation of the bill for the payment. This method returns two things: (1) The
   * string representation of the bill and (2) the balance amount on the bill after the total cost
   * is subtracted from the paid amount. An example of the bill generated from this method is
   * provided inline below.
   *
   * @param paymentId an identifier for the {@link Payment} event to be processed
   * @param payment the {@link Payment} activity to be processed that contains all the details about
   *     the items, amount and {@link PaymentType}
   * @return a {@link Bill} that contains the payment details and a string representation of the
   *     bill
   */

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

  

  public static Bill generateBill(UUID paymentId, Payment payment) {
    BillGenerator.initServiceEndpoints();


    float total = 0;
    StringBuilder billBuilder = new StringBuilder();
    billBuilder.append(billHeader(paymentId));
    // append an entry per purchase item to the bill
    int itemIndex = 1;
    List<String> itemlist = new ArrayList<>();

    for (PaymentUnit pu : payment.getUnitList()) {
      billBuilder.append(billItem(itemIndex, pu));
      total += pu.getTotalCost().floatValue();
      itemIndex++;
      itemlist.add(pu.getName());
    }
    
    Gson gson = new Gson();

    // Create a Map to represent the JSON structure
    Map<String, Object> requestBodyMap = new HashMap<>();
    String message = "with the following ingredients" + itemlist.toString() + ", what recipes can i make?";
    requestBodyMap.put("message", message);
    requestBodyMap.put("item_flag", false);
    
    // String encodedMessage = UriUtils.encodeQueryParam(message, StandardCharsets.UTF_8);
    String requestBody = gson.toJson(requestBodyMap);
    LOGGER.info(message);
    String endpoint = LLM_SERVICE + LLM_EP;
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
          String responseRecipe = response.body();
          Gson gson_response = new Gson();
          JsonObject jsonRecipe = gson_response.fromJson(responseRecipe,JsonObject.class);
          content = jsonRecipe.get("content").getAsString();

          LOGGER.info(String.format(
                  "'%s' Response: \n%s",
                  endpoint, content));

        }
      }     
        catch (IOException | InterruptedException e) {
        LOGGER.error(String.format("Failed to fetch recipes '%s'", endpoint), e);
      }
          
    billBuilder.append(BILL_HEADER);
    float tax = Double.valueOf(total * TAX_VALUE).floatValue();
    float paid = payment.getPaidAmount().floatValue();
    float balance = paid - total - tax;
    billBuilder.append(infoLine(TOTAL, total));
    billBuilder.append(infoLine(TAX, tax));
    billBuilder.append(infoLine(PAID, paid));
    billBuilder.append(infoLine(BALANCE, balance));
    billBuilder.append(BILL_HEADER);

    for (String line : content.split("\\n")) {
      billBuilder.append(formatLine(line, BILL_HEADER.length())).append("\n");
  }

    LOGGER.info(String.format("Processed payment:\n%s", billBuilder));
    //  ----------------------------------------------------------------------------
    //                Payment id: 02beba81-e19f-4543-9823-261db722ed02
    //  ----------------------------------------------------------------------------
    //      1. 5x BigBurger (02beba81-e19f-4543-9823-261db722ed02):           $34.44
    //      2. 4x DoubleBurger (4df41297-a96f-4602-8059-df3b0e4071cb):         $21.2
    //  ----------------------------------------------------------------------------
    //    Total:                                                              $55.64
    //    Tax:                                                                 $8.31
    //    Paid:                                                             $5000.00
    //    Balance:                                                          $4936.04
    //  ----------------------------------------------------------------------------


    return new Bill()
        .setPayment(payment)
        .setStatus(PaymentStatus.SUCCESS)
        .setBalance(new BigDecimal(String.format("%.2f", balance)))
        .setPrintedBill(billBuilder.toString());
  }

  /**
   * Utility method that generates the header section of the bill.
   *
   * @param paymentId the id to be included in the header of the bill identifying this specific
   *     payment
   * @return a string containing the header for the generated bill
   */
  private static String billHeader(UUID paymentId) {
    return BILL_HEADER
        + String.format("              Payment id: %s              \n", paymentId)
        + BILL_HEADER;
  }

  /**
   * Utility method that generates a single line entry for a specific item on a bill.
   *
   * @param itemIndex the place of this item in the bill so that the line starts with this number
   * @param paymentUnit the {@link PaymentUnit} object that contains details about the item in the
   *     payment event for which an entry is being generated
   * @return a string that contains a line with details about the purchase of one specific item that
   *     can be appended to the bill
   */
  private static String billItem(int itemIndex, PaymentUnit paymentUnit) {
    StringBuilder sb = new StringBuilder();
    UUID unitId = paymentUnit.getItemId();
    String unitName = paymentUnit.getName();
    Number totalUnitValue = paymentUnit.getTotalCost();
    BigDecimal unitQuantity = paymentUnit.getQuantity();
    String leadingStr =
        String.format("    %s. %sx %s (%s):", itemIndex, unitQuantity, unitName, unitId);
    // get length of the current line so far
    int leadingLength = leadingStr.length();
    // get length of the total cost for this item
    int costLength = totalUnitValue.toString().length();
    // calculate the number of spaces between the item description and the total cost
    // -2 for the dollar $ sign and the newline
    int middleSpaces = BILL_HEADER.length() - leadingLength - costLength - 2;
    sb.append(leadingStr);
    sb.append(spaces(middleSpaces));
    sb.append(String.format("$%s\n", totalUnitValue));
    return sb.toString();
  }

  /**
   * Utility method to add a line for some specific detail like total, balance and paid amount. The
   * method takes in a string explaining what the line is for (e.g. Total or Balance) and the value.
   * Using these two a line is generated that can be appended to the end of the bill.
   *
   * @param infoType a string explaining what the line is about which will be appended to the
   *     beginning of the generated line
   * @param value the numeric value of the information line that is to be generated (e.g. the total
   *     value, the balance)
   * @return the generated line with the information type at the beginning followed by spaces and
   *     the numeric value at the end formatted to 2 decimal points.
   */
  private static String infoLine(String infoType, float value) {
    StringBuilder sb = new StringBuilder();
    String formattedValue = String.format("%.2f", value);
    int spacesToAdd = BILL_HEADER.length() - infoType.length() - formattedValue.length() - 2;
    sb.append(infoType);
    sb.append(spaces(spacesToAdd));
    sb.append(String.format("$%s\n", formattedValue));
    return sb.toString();
  }

  private static String formatLine(String line, int margin) {
    StringBuilder formattedLine = new StringBuilder();
    List<String> wrappedLines = wordWrap(line, margin); //Use the word wrap method from before.

    for (String wrappedLine : wrappedLines) {
        formattedLine.append(wrappedLine).append("\n"); // Append each wrapped line with a newline
    }
    return formattedLine.toString().trim(); // Trim to remove the trailing newline
  }
  
  private static List<String> wordWrap(String text, int margin) {
    List<String> lines = new ArrayList<>();
    StringBuilder currentLine = new StringBuilder();

    String[] words = text.split(" ");

    for (String word : words) {
        if (currentLine.length() + word.length() + 1 <= margin) {
            if (currentLine.length() > 0) {
                currentLine.append(" ");
            }
            currentLine.append(word);
        } else {
            lines.add(currentLine.toString());
            currentLine = new StringBuilder(word);
        }
    }

    if (currentLine.length() > 0) {
        lines.add(currentLine.toString());
    }

    return lines;
  }
  
  /**
   * Utility method that takes in a number and creates a concatenation of that many spaces
   *
   * @param count number indicating how many times space is to be appended
   * @return a string that is has 'count' many times spaces
   */
  private static String spaces(int count) {
    StringBuilder sb = new StringBuilder();
    while (count > 0) {
      sb.append(SPACE);
      count--;
    }
    return sb.toString();
  }
}
