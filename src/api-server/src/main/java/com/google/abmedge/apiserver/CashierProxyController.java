// // Copyright 2022 Google LLC
// //
// // Licensed under the Apache License, Version 2.0 (the "License");
// // you may not use this file except in compliance with the License.
// // You may obtain a copy of the License at
// //
// //      http://www.apache.org/licenses/LICENSE-2.0
// //
// // Unless required by applicable law or agreed to in writing, software
// // distributed under the License is distributed on an "AS IS" BASIS,
// // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// // See the License for the specific language governing permissions and
// // limitations under the License.

// package com.google.abmedge.apiserver;

// import java.net.http.HttpClient;
// import java.nio.charset.Charset;
// import java.time.Duration;
// import java.util.Enumeration;

// import javax.annotation.PostConstruct;
// import javax.servlet.http.HttpServletRequest;

// import org.apache.commons.lang3.StringUtils;
// import org.apache.logging.log4j.LogManager;
// import org.apache.logging.log4j.Logger;
// import org.springframework.http.HttpEntity;
// import org.springframework.http.HttpHeaders;
// import org.springframework.http.HttpMethod;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.GetMapping;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.RestController;
// import org.springframework.web.client.RestTemplate;
// import org.springframework.http.converter.StringHttpMessageConverter;

// import com.google.gson.Gson;

// /**
//  * This is the main controller class for the api-server service defines the APIs exposed by the
//  * service. The controller also defines 2 APIs (/ready and /healthy) for readiness and health
//  * checkups
//  */
// @RestController
// @RequestMapping("/ui")
// public class CashierProxyController {

//   private static final Logger LOGGER = LogManager.getLogger(CashierProxyController.class);
//   private static final String FAILED = "Failed to fetch items; try again after a while";
//   private static final String INVENTORY_EP_ENV = "INVENTORY_EP";
//   private static final String PAYMENTS_EP_ENV = "PAYMENTS_EP";
//   private static final String LLM_EP_ENV = "LLM_EP";
//   private static final String CASHIER_EP_ENV = "CASHIER_EP";
//   private static final String ITEMS_EP = "/items";
//   private static final String ITEMS_SEARCH_EP = "/search";
//   private static final String TYPES_EP = "/types";
//   private static final String ITEMS_BY_ID_EP = "/items_by_id";
//   private static final String SWITCH_EP = "/switch";
//   private static final String UPDATE_EP = "/update";
//   private static final String PAY_EP = "/pay";
//   private static final HttpClient HTTP_CLIENT =
//       HttpClient.newBuilder()
//           .version(HttpClient.Version.HTTP_1_1)
//           .connectTimeout(Duration.ofSeconds(11))
//           .build();
//   /**
//    * default service endpoints to use if they cannot be read from environment variables in {@link
//    * #initServiceEndpoints()}
//    */

//   private static String CASHIER_SERVICE = "http://cashier-frontend.cashier.svc.cluster.local:8080/ui/cashier/";

//   private static final Gson GSON = new Gson();

//   private final RestTemplate restTemplate = new RestTemplate();

//   @PostConstruct
//   void init() {
//     initServiceEndpoints();
//   }

//   private void initServiceEndpoints() {
//     String cashier = System.getenv(CASHIER_EP_ENV);

//     if (StringUtils.isNotBlank(cashier)) {
//       LOGGER.info(String.format("Setting cashier service endpoint to: %s", cashier));
//       CASHIER_SERVICE = cashier;
//     } else {
//       LOGGER.warn(
//           String.format(
//               "Could not read environment variable %s; thus defaulting to %s",
//               CASHIER_EP_ENV, CASHIER_SERVICE));
//     }

//     restTemplate.getMessageConverters()
//         .add(0, new StringHttpMessageConverter(Charset.forName("UTF-8")));
//   }

//   @GetMapping("/cashier/**")
//   public ResponseEntity<String> cashierGet(HttpServletRequest request, HttpEntity<String> httpEntity) {
//       return proxyRequest(request, httpEntity, HttpMethod.GET, CASHIER_SERVICE, "/ui/cashier/");
//   }

//   @PostMapping("/cashier/**")
//   public ResponseEntity<String> cashierPost(HttpServletRequest request, HttpEntity<String> httpEntity) {
//       return proxyRequest(request, httpEntity, HttpMethod.POST, CASHIER_SERVICE, "/ui/cashier/");
//   }

//   private ResponseEntity<String> proxyRequest(HttpServletRequest request, HttpEntity<String> httpEntity, HttpMethod method, String targetUrl, String proxyPath) {
//         String subPath = extractSubPath(request, proxyPath);
//         String target = targetUrl + subPath;

//         HttpHeaders headers = new HttpHeaders();
//         Enumeration<String> headerNames = request.getHeaderNames();
//         while (headerNames.hasMoreElements()) {
//             String headerName = headerNames.nextElement();
//             Enumeration<String> headerValues = request.getHeaders(headerName);
//             while (headerValues.hasMoreElements()) {
//                 headers.add(headerName, headerValues.nextElement());
//             }
//         }

//         //Remove host header to avoid issues with target service.
//         headers.remove(HttpHeaders.HOST);

//         HttpEntity<String> targetHttpEntity = new HttpEntity<>(httpEntity.getBody(), headers);

//         try {
//             ResponseEntity<String> response = restTemplate.exchange(target, method, targetHttpEntity, String.class);
//             LOGGER.info(response.getBody());
//             HttpHeaders responseHeaders = new HttpHeaders();
//             responseHeaders.setContentType(response.getHeaders().getContentType());
//             return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
//         } catch (Exception e) {
//             // Handle exceptions (e.g., log, return error response)
//             return ResponseEntity.status(500).body("Proxy error: " + e.getMessage());
//         }
//     }

//     private String extractSubPath(HttpServletRequest request, String proxyPath) {
//         String path = request.getRequestURI();
//         int proxyIndex = path.indexOf(proxyPath) + proxyPath.length();
//         return path.substring(proxyIndex);

//     }
// }
