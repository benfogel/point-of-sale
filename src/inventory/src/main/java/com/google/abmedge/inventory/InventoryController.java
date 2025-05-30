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

package com.google.abmedge.inventory;

import java.io.IOException;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import com.google.abmedge.inventory.InventoryController.SearchRequest;
import com.google.abmedge.inventory.dao.DatabaseConnector;
import com.google.abmedge.inventory.dao.InventoryStoreConnector;
import com.google.abmedge.inventory.dto.Inventory;
import com.google.abmedge.inventory.util.InventoryStoreConnectorException;
import com.google.abmedge.payment.PurchaseItem;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

/**
 * This is the main controller class for the inventory service defines the APIs exposed by the
 * service. The controller also defines 2 APIs (/ready and /healthy) for readiness and health
 * checkups
 */
@RestController
public class InventoryController {

  private static final Logger LOGGER = LogManager.getLogger(InventoryController.class);
  private static final String ACTIVE_TYPE_ENV_VAR = "ACTIVE_ITEM_TYPE";
  private static final String INVENTORY_ITEMS_ENV_VAR = "ITEMS";
  private static final String ALL_ITEMS = "ALL";
  private static final Gson GSON = new Gson();
  private static final HttpClient HTTP_CLIENT =
  HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(11))
      .build();
  private static final String LLM_EP_ENV = "LLM_EP";
  private static final String LLM_EP = "/chat";
  private static String PAYMENTS_SERVICE = "http://payments-svc:8080";
  // private static  String LLM_SERVICE = "http://localhost:8888";
  private static  String LLM_SERVICE = "http://next-action-agent-svc.next-action-assistant:80";

  // the context of the inventory service (e.g. textile, food, electronics, etc)
  private String activeItemsType;
  private InventoryStoreConnector activeConnector;
  private final DatabaseConnector databaseConnector;

  // DatabaseConnector is autowired via Spring
  public InventoryController(DatabaseConnector databaseConnector) {
    this.databaseConnector = databaseConnector;
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

  /**
   * This method runs soon after the object for this class is created on startup of the application.
   */
  @PostConstruct
  void init() {
    initServiceEndpoints();
    initConnectorType();
    initItemsType();
    initInventoryItems();
  }

  @RequestMapping("/")
  public String home() {
    return "Hello Anthos BareMetal - Inventory Controller";
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

  @GetMapping(value = "/items", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> items() {
    List<Item> inventoryItems =
        activeItemsType.equals(ALL_ITEMS)
            ? activeConnector.getAll()
            : activeConnector.getAllByType(activeItemsType);
    String jsonString = GSON.toJson(inventoryItems, new TypeToken<List<Item>>() {}.getType());
    return new ResponseEntity<>(jsonString, HttpStatus.OK);
  }

  @PostMapping(value = "/itemsUpsell", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> itemsUpsell(@RequestBody String UpsellNotPopulatedArray) {
    LOGGER.info(UpsellNotPopulatedArray);
    // JsonObject jsonObject = JsonParser.parseString(UpsellNotPopulatedArray).getAsJsonObject();
    // JsonArray upsellItemsArray = jsonObject.getAsJsonArray("upsellItems");
    JsonElement jsonElement= JsonParser.parseString(UpsellNotPopulatedArray);
    LOGGER.info(jsonElement.toString());

    JsonObject jsonObject = jsonElement.getAsJsonObject();
    Map<String,Item> getItem = getItemNameToItem();
    
    LOGGER.info(getItem.toString());

    for (JsonElement elem : jsonObject.getAsJsonArray("upsellItems")) {
      LOGGER.info(String.format("PARSING"));
      JsonObject upsellEntry = elem.getAsJsonObject();
      LOGGER.info(upsellEntry.toString());
      if (upsellEntry.has("upsellItem") && upsellEntry.get("upsellItem").isJsonObject()) {
        JsonObject upsellItemObject = upsellEntry.getAsJsonObject("upsellItem");
        String name=upsellItemObject.get("name").getAsString();
        LOGGER.info(upsellItemObject.toString());

        
        Item matchedInventoryItem = getItem.get(name.toLowerCase());
        
        if(matchedInventoryItem != null) {      
          LOGGER.info(matchedInventoryItem.toString());         
          if (matchedInventoryItem.getLabels() != null) {
            JsonArray labelsArray = new JsonArray();
            for (String label : matchedInventoryItem.getLabels()) {
                labelsArray.add(label);
            }
            upsellItemObject.add("labels", labelsArray); 
          } else {
            upsellItemObject.add("labels", new JsonArray()); 
          }

          if (matchedInventoryItem.getId() != null) { 
            upsellItemObject.addProperty("UUID", matchedInventoryItem.getId().toString());
          }

          upsellItemObject.addProperty("Quantity", matchedInventoryItem.getQuantity());

          if (matchedInventoryItem.getPrice() != null) {
              upsellItemObject.addProperty("Price", matchedInventoryItem.getPrice());
          }

          if (matchedInventoryItem.getImageUrl() != null) {
              upsellItemObject.addProperty("ImageURL", matchedInventoryItem.getImageUrl());
          }

        }
        else {
          LOGGER.info(String.format("Item not found"));
        }
        
      LOGGER.info(upsellItemObject.toString());
      }
        
    }
    
    LOGGER.info(jsonObject.toString());
    return new ResponseEntity<>(jsonObject.toString(), HttpStatus.OK);
  }

  public static class SearchRequest {
    private String message;
    private boolean item_flag;

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public boolean isItem_flag() {
      return item_flag;
    }

    public void setItem_flag(boolean item_flag) {
      this.item_flag = item_flag;
    }
  }

  // Currently this pulls from the LLM + RAG then ingest the item if it does not exist. 
  @PostMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> search(@RequestBody SearchRequest searchRequest) {

    Gson gson = new Gson();

    Map<String, Object> requestBodyMap = new HashMap<>();

    requestBodyMap.put("message", searchRequest.message);
    requestBodyMap.put("item_flag", true);
    
    String requestBody = gson.toJson(requestBodyMap);
    LOGGER.info(searchRequest.message);
    String endpoint = LLM_SERVICE + LLM_EP;
    JsonObject jsonItems= new JsonObject();

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
          String responseIngredients = response.body();
          Gson gson_response = new Gson();
          jsonItems = gson_response.fromJson(responseIngredients,JsonObject.class);


        }
      }     
        catch (IOException | InterruptedException e) {
        LOGGER.error(String.format("Failed to fetch recipes '%s'", endpoint), e);
      }

    
    JsonElement rootElement = JsonParser.parseString(jsonItems.get("content").toString());
    JsonObject rootObject = GSON.fromJson(rootElement.getAsString(),JsonObject.class);
  //   JsonArray items = GSON.fromJson(rootObject.get("items").toString(),JsonArray.class);
  //   //JsonObject items = content.get("items").getAsJsonObject();
  //   //String content=jsonItems.get("content").toString();

  //   LOGGER.info(String.format(
  //           "'%s' Response: \n%s",
  //           endpoint, items.toString()));

  //   List<Item> itemlist= new ArrayList<>();
  //   for (JsonElement itemElement : items) {
  //     if (itemElement.isJsonObject()) {
  //         LOGGER.info(String.format("Printing %s",itemElement.toString()));
  //         String name=itemElement.getAsJsonObject().get("name").toString();
  //         String type=itemElement.getAsJsonObject().get("type").toString();
  //         String price=itemElement.getAsJsonObject().get("price").toString();
  //         String imageUrl=itemElement.getAsJsonObject().get("imageUrl").toString();
  //         String quantity=itemElement.getAsJsonObject().get("quantity").toString();
          
  //         Item item = new Item();
  //         item.setName(name);
  //         item.setType(type);
  //         item.setPrice(new BigDecimal(price));
  //         item.setImageUrl(imageUrl);
  //         item.setQuantity(100);
  //         // UUID uuid = UUID.randomUUID();
  //         // item.setId(uuid);   
  //         itemlist.add(item);
  //     } else {
  //         System.err.println("Warning: Skipping non-JsonObject element in 'items' array: " + itemElement);
  //     }
  // }

   
    Inventory inventory = GSON.fromJson(rootObject, Inventory.class);
    Map<String, Set<String>> itemTypeToNameMap = getItemTypeToNamesMap();
    Map<String, UUID> getItemNameToIdMap = getItemNameToIdMap();
    
    List<Item> itemlist_uuid= new ArrayList<>();
    for (Item a : inventory.getItems()) {
      LOGGER.info(String.format("%s",a.toString()));
      if( !insertIfNotExists(a, itemTypeToNameMap)){
        // exists . need to get the itemID by 
        LOGGER.info(String.format("Already Exists.",a.getId()));
        UUID itemId = getItemNameToIdMap.get(a.getName());
        a.setId(itemId);
        itemlist_uuid.add(a);
    } else{
      LOGGER.info(String.format("Created new.",a.getId()));
      itemlist_uuid.add(a);}
    }
    Inventory inventory_uuid= new Inventory(); 
    inventory_uuid.setItems(itemlist_uuid);
    String jsonitemlists = GSON.toJson(inventory_uuid.getItems(), new TypeToken<List<Item>>() {}.getType());


    return new ResponseEntity<>(jsonitemlists, HttpStatus.OK);
  }

  @GetMapping(value = "/types", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> types() {
    Set<String> itemTypes = activeConnector.getTypes();
    String jsonString = GSON.toJson(itemTypes, new TypeToken<Set<String>>() {}.getType());
    return new ResponseEntity<>(jsonString, HttpStatus.OK);
  }

  @PostMapping(
      value = "/items_by_id",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> items(@RequestBody List<String> idList) {
    List<Item> inventoryItems =
        idList.stream()
            .map(id -> activeConnector.getById(UUID.fromString(id)))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());
    String jsonString = GSON.toJson(inventoryItems, new TypeToken<List<Item>>() {}.getType());
    return new ResponseEntity<>(jsonString, HttpStatus.OK);
  }

  /**
   * Every item managed by the inventory service is expected to have a 'type'. The type is used to
   * group items into a single context. The grouping can be based on any aspect that makes sense for
   * the deployment (e.g. textile, food, electronics, etc).
   *
   * <p>This method takes in a specific inventory type and changes the current context of the
   * inventory service to that specific type by setting the {@link
   * InventoryController#activeItemsType} variable. The inventory service APIs respond to requests
   * by only loading and looking at the items in the inventory that match the current active 'type'.
   *
   * @param type the type to which the current context is to be switched to
   * @return HTTP 200 if the switch is done without any errors
   */
  @PostMapping(value = "/switch/{type}")
  public ResponseEntity<Void> switchType(@PathVariable String type) {
    this.activeItemsType = type;
    LOGGER.info(
        String.format("The active inventory type has been changed to: %s", activeItemsType));
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * This method serves as the mapping of the '/update' API in the controller. This method takes in
   * a list of {@link PurchaseItem}s and updates them in the underlying datastore exposed via the
   * active {@link InventoryStoreConnector}. The details as to how/what the update operation does is
   * specific to the implementation of {@link InventoryStoreConnector} that is used.
   *
   * @param purchaseList a list of {@link PurchaseItem} objects that needs to be updated in the
   *     underlying datastore
   * @return an object of {@link ResponseEntity} that only has an HTTP code without any payload
   */
  @PutMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> update(@RequestBody List<PurchaseItem> purchaseList) {
    for (PurchaseItem pi : purchaseList) {
      UUID itemId = pi.getItemId();
      Optional<Item> loadedItem = activeConnector.getById(itemId);
      try {
        if (loadedItem.isEmpty() || !updateItem(loadedItem.get(), pi)) {
          LOGGER.warn(String.format("Update attempt with invalid item id: '%s'", itemId));
          throw new Exception();
        }
      } catch (Exception e) {
        LOGGER.error("Failed to update one or more items in the purchase list!", e);
        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
      }
    }
    return new ResponseEntity<>(HttpStatus.OK);
  }

  private boolean updateItem(Item item, PurchaseItem purchaseItem)
      throws InventoryStoreConnectorException {
    long currentQuantity = item.getQuantity();
    if (currentQuantity < purchaseItem.getItemCount()) {
      LOGGER.error(
          String.format(
              "Failed to update item '%s - %s'. "
                  + "The requested count '%s' is more than whats available '%s'",
              purchaseItem.getItemId(),
              item.getName(),
              purchaseItem.getItemCount(),
              currentQuantity));
      return false;
    }

    long newQuantity = currentQuantity - purchaseItem.getItemCount();
    item.setQuantity(newQuantity);
    activeConnector.update(item);
    LOGGER.info(
        String.format(
            "Updated item '%s - %s' with new quantity '%s'",
            purchaseItem.getItemId(), item.getName(), newQuantity));
    return true;
  }

  /**
   * This method initializes the connector that will be used to connect to the storage system that
   * holds all the items' information. The connector should implement the interface {@link
   * InventoryStoreConnector}. A running instance of the application shall have multiple
   * implementations of {@link InventoryStoreConnector}. However, only one of them will be active at
   * any single given time. Once, set to change the connector type a restart of the application is
   * required.
   *
   * <p>Previously, we would look up an environment variable to decide the {@link #activeConnector}
   * to be between an InMemoryConnector and a DatabaseConnector. However, with the latest changes we
   * only have one connector {@link DatabaseConnector}.
   *
   * <p>The datasource this connector connects to is either an external MySQL DB or an Embedded H2
   * DB. The choice for which one to connect to decided based on the 'SPRING_PROFILES_ACTIVE'
   * environment variable. Depending on the value of this environment variable (empty or inmemory or
   * database) a Spring profile is automatically configured and thus the corresponding
   * `application-{profile}.properties` file is loaded. See the properties files under resources/
   * for the configs for the two DB options: MySQL and Embedded.
   */
  private void initConnectorType() {
    activeConnector = databaseConnector;
  }

  /**
   * This method initializes the type of items to be served via the inventory API. Each item in the
   * inventory has a type associated to it. Thus, the inventory API can be configured to serve only
   * items of a certain type. Thus, on startup the decision for which item types are to be served is
   * decided by looking at the value of the environment variable 'ACTIVE_ITEM_TYPE'.
   *
   * <p>If this environment variable is not set then by default the inventory API is configured to
   * serve all types of items without any filtering. Any value that is set for this environment
   * variable should be an exact match to the value that is set against the 'type' attribute of each
   * item that set against the 'ITEMS' environment variable as explained in {@link
   * #initInventoryItems()}
   */
  private void initItemsType() {
    activeItemsType = System.getenv(ACTIVE_TYPE_ENV_VAR);
    if (StringUtils.isBlank(activeItemsType)) {
      LOGGER.warn(
          String.format(
              "'%s' environment variable is not set; " + "thus defaulting to: %s",
              ACTIVE_TYPE_ENV_VAR, ALL_ITEMS));
      activeItemsType = ALL_ITEMS;
    }
    LOGGER.info(String.format("Active items type is: %s", activeItemsType));
  }

  /**
   * This method loads the list of supported inventory items from the environmental variable
   * 'ITEMS'. If the variable is not set or is empty then there is nothing that is added to the
   * inventory store via the {@link #activeConnector}. The expected format for the environment
   * variable's value is that of a yaml file containing multiple 'item' definitions. Once, set to
   * reload new items into the inventory a restart of the application is required.
   *
   * <pre>
   * E.g:
   *    {@code System.getenv('ITEMS')} should return something as follows:
   *    items:
   *      - name: "BigBurger"
   *        type: "burgers"
   *        price: 5.50
   *        imageUrl: "usr/lib/images/bigburger.png"
   *        quantity: 200
   *        labels: [ "retail", "restaurant", "food" ]
   *      - name: "DoubleBurger"
   *        type: "burgers"
   *        price: 7.20
   *        imageUrl: "usr/lib/images/burgers.png"
   *        quantity: 200
   *        ....
   * </pre>
   */
  private void initInventoryItems() {
    String inventoryList = System.getenv(INVENTORY_ITEMS_ENV_VAR);
    if (StringUtils.isBlank(inventoryList)) {
      LOGGER.warn("No items found under inventory list env var '{}'", INVENTORY_ITEMS_ENV_VAR);
      return;
    }
    inventoryList = inventoryList.replaceAll("\\\\n", "\n");
    LOGGER.debug(inventoryList);
    Map<String, Set<String>> itemTypeToNameMap = getItemTypeToNamesMap();
    Yaml yaml = new Yaml(new Constructor(Inventory.class, new LoaderOptions()));
    Inventory inventory = yaml.load(inventoryList);
    inventory.getItems().forEach(i -> insertIfNotExists(i, itemTypeToNameMap));
  }

  private Map<String, Set<String>> getItemTypeToNamesMap() {
    Map<String, Set<String>> itemTypeToNameMap = new HashMap<>();
    List<Item> loadedItems = activeConnector.getAll();
    loadedItems.forEach(
        i -> {
          Set<String> itemNames =
              itemTypeToNameMap.computeIfAbsent(i.getType(), k -> new HashSet<>());
          itemNames.add(i.getName());
        });
    return itemTypeToNameMap;
  }

  public Map<String, UUID> getItemNameToIdMap() {
    Map<String, UUID> itemNameToIdMap = new HashMap<>();
    List<Item> loadedItems = activeConnector.getAll();

    loadedItems.forEach(item -> itemNameToIdMap.put(item.getName(), item.getId()));

    return itemNameToIdMap;
  }
  
  public Map<String, Item> getItemNameToItem() {
    Map<String, Item> itemNameToIdMap = new HashMap<>();
    List<Item> loadedItems = activeConnector.getAll();

    loadedItems.forEach(item -> itemNameToIdMap.put(item.getName(), item));
    return itemNameToIdMap;
  }

  private Boolean insertIfNotExists(Item i, Map<String, Set<String>> itemTypeToNameMap) {
    if (itemTypeToNameMap.containsKey(i.getType())
        && itemTypeToNameMap.get(i.getType()).contains(i.getName())) {
      LOGGER.warn(
          "Item ['type': {}, 'name': {}] already exists. Skipping..", i.getType(), i.getName());
      return false;
    }
    try {
      activeConnector.insert(i);
    } catch (InventoryStoreConnectorException e) {
      LOGGER.error("Failed to insert item '{}' of type '{}'", i.getName(), i.getType(), e);
    }
    LOGGER.info(String.format("Inserting new item: %s", i));
    return true;
  }
}