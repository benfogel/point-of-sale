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

package com.google.abmedge.inventory.dao;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.util.Streamable;
import org.springframework.stereotype.Component;

import com.google.abmedge.inventory.Item;
import com.google.abmedge.inventory.ItemRepository;
import com.google.abmedge.inventory.util.InventoryStoreConnectorException;

/**
 * An implementation of the {@link InventoryStoreConnector} that connects to the database to persist
 * and retrieve inventory information. This implementation uses an implementation of {@link
 * org.springframework.data.repository.CrudRepository} -> {@link ItemRepository} to access the DB
 */
@Component
public class DatabaseConnector implements InventoryStoreConnector {

  private final ItemRepository itemRepository;

  public DatabaseConnector(ItemRepository itemRepository) {
    this.itemRepository = itemRepository;
  }

  @Override
  public List<Item> getAll() {
    return Streamable.of(itemRepository.findAll()).toList();
  }

  @Override
  public List<Item> getAllByType(String type) {
    return Streamable.of(itemRepository.findAll()).stream()
        .filter(i -> i.getType().equals(type))
        .collect(Collectors.toList());
  }

  @Override
  public Set<String> getTypes() {
    return Streamable.of(itemRepository.findAll()).stream()
        .map(Item::getType)
        .collect(Collectors.toSet());
  }

  @Override
  public Optional<Item> getById(UUID id) {
    return itemRepository.findById(id);
  }

  @Override
  public void insert(Item item) throws InventoryStoreConnectorException {
    itemRepository.save(item);
  }

  @Override
  public void insert(List<Item> items) throws InventoryStoreConnectorException {
    for (Item i : items) {
      insert(i);
    }
  }

  @Override
  public void update(Item item) throws InventoryStoreConnectorException {
    itemRepository.save(item);
  }

  @Override
  public void delete(UUID id) throws InventoryStoreConnectorException {
    itemRepository.deleteById(id);
  }

  @Override
  public void delete(List<UUID> ids) {
    itemRepository.deleteAllById(ids);
  }
}
