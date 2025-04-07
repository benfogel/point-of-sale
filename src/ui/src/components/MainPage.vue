<!--
 Copyright 2022 Google LLC

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<template>
  <div class="container-fluid">
    <div class="row" id="pos">
      <div class="col-md-8 col-md-offset-2">
        <nav class="navbar navbar-light bg-light">
          <span class="navbar-brand mb-0 h1">
            <img src="../assets/logo.gif"
                 alt="Google Cloud"
                 width="30"
                 height="24">
            Google Cloud - Self check-out
          </span>
        </nav>

        <div class="row">
          <div class="col-md-6">
            <b-card
              border-variant="secondary"
              header="Can't find an item?"
              header-bg-variant="secondary"
              header-text-variant="white"
              align="center">
              Use the search box below to describe your item
              to add to the cart.
              <b-form inlin class="find-form">
                <label
                  class="sr-only"
                  for="inline-form-input-username"
                >Item Description</label>
                <b-form-input
                  id="inline-form-input-username"
                  v-model="findItemDescription"
                  placeholder="Describe the item"
                  class="col-md-4"
                ></b-form-input>
                <b-button
                  class="col-md-12"
                  variant="secondary"
                  @click="findItem"
                >Find Item</b-button>
              </b-form>
            </b-card>
            <div class="row">
              <div
                v-for="(item, index) in foundItems"
                :key="index"
                class="col-md-4 found-item"
                align="center">
                {{  item.name }} -
                ${{  item.price }}
                <b-button
                  variant="primary"
                  @click="addFoundItem(item)"
                >Add Item</b-button>
              </div>
            </div>
            <item-list :items="items" @add="addItem"></item-list>
          </div>
          <div class="col-md-6">
            <transaction-view
              ref="transactions"
              :items="currentItems"
              @remove="removeItem"
              @edit="toggleEdit"
              @pay="toggleUpsell"
              @clear="clear"
            ></transaction-view>

            <div v-if="hasBill" class="bill-container">
              <h2>Bill:</h2>
              <pre class="print-container">
                {{ bill }}
                <img src="../assets/qr.svg"
                  alt="Google Cloud"
                  width="120"
                  height="120"
                  class="mx-auto d-block">
              </pre>
            </div>
          </div>
        </div>
      </div>
      <div class="col-md-2">

      </div>
    </div>

    <b-modal
      ref="upsellModal"
      hide-footer
      title="Recommendations"
      size="xl">
      <div class="d-block text-center">
        <div v-if="loadingUpsell"
          class="spinner-border"
          role="status">
          <span class="sr-only">
            Loading...
          </span>
        </div>

        <h3 v-if="!loadingUpsell">View the following items</h3>
        <div v-if="!loadingUpsell" class="row">
          <div
            v-for="(item, index) in upsellItems"
            :key="index"
            class="col-md-4 upsell-item"
            align="center">
            <div class="card">
              <div class="card-body">
              <h5 class="card-title">{{  item.name }}</h5>
              <h6 class="card-subtitle mb-2 text-muted">
                {{ item.price }}
              </h6>
              <p class="card-text">
                {{ item.recipeSuggestion }}
              </p>
              <a v-if="!item.added"
                href="#"
                class="card-link"
                @click="addUpsellItem(item)">
                Add to cart
              </a>
              <a v-if="item.added"
                href="#"
                class="card-link">
                Added!
              </a>
              </div>
            </div>
          </div>
        </div>
        <div class="row">
          <b-button
            class="mt-3"
            variant="outline-info"
            block
            @click="cancelUpsell">Cancel</b-button>
        </div>
        <div class="row">
          <b-button
            class="mt-2"
            variant="outline-primary"
            block
            @click="completeSale">Complete Payment</b-button>
        </div>
      </div>
    </b-modal>
  </div>
</template>

<script>
import RetailEdgeAppApi from '@/services/RetailEdge';
import ItemList from './ItemList.vue';
import TransactionView from './TransactionView.vue';

export default {
  name: 'MainPage',
  title: 'Retail Edge POS',
  components: {
    ItemList,
    TransactionView,
  },
  props: {},
  data: () => {
    return {
      items: [],
      lineItems: [],
      storeTypes: [],
      printedBill: null,
      findItemDescription: '',
      foundItems: [],
      upsellItems: [],
      loadingUpsell: false,
    };
  },
  computed: {
    currentItems() {
      return [...this.lineItems];
    },
    bill() {
      return this.printedBill;
    },
    hasBill() {
      return !!this.bill;
    },
  },
  async created() {
    await this.loadStoreTypes();
    await this.loadItems();
  },
  methods: {
    async loadStoreTypes() {
      const response = await RetailEdgeAppApi.types();
      if (response.status !== 200 && response.status !== 204) {
        this.notifyFailure('Failed to load store types!');
        return;
      }
      const data = await response.json();
      this.storeTypes = data.reduce((acc, curr) => {
        return [{value: curr}, ...acc];
      }, []);
    },
    async loadItems() {
      const response = await RetailEdgeAppApi.items();
      if (response.status !== 200 && response.status !== 204) {
        this.notifyFailure('Failed to load items!');
        return;
      }
      const data = await response.json();
      this.items = data.reduce((acc, curr) => {
        acc = [curr, ...acc];
        return acc;
      }, []);
    },
    addItem(item) {
      let found = false;
      for (let i = 0; i < this.lineItems.length; i++) {
        if (this.lineItems[i].item.id === item.id) {
          this.lineItems[i].numberOfItems++;
          found = true;
          break;
        }
      }
      if (!found) {
        this.lineItems.push({item: item, numberOfItems: 1, editing: false});
      }
    },
    addFoundItem(item) {
      this.addItem(item);
      this.foundItems = [];
    },
    addUpsellItem(upsellItem) {
      upsellItem.added = true;
      this.addItem(upsellItem);
    },
    async findItem() {
      const response = await RetailEdgeAppApi.search(this.findItemDescription);
      if (response.status !== 200 && response.status !== 204) {
        this.notifyFailure('Failed to search for items!');
        return;
      }

      const data = await response.json();
      this.foundItems = data.reduce((acc, curr) => {
        acc = [curr, ...acc];
        return acc;
      }, []);
    },
    removeItem(item) {
      for (let i = 0; i < this.lineItems.length; i++) {
        if (this.lineItems[i] === item) {
          this.lineItems.splice(i, 1);
          break;
        }
      }
    },
    toggleEdit(item) {
      item.editing = !item.editing;
    },
    toggleUpsell() {
      this.loadingUpsell = true;
      this.upsellItems = [];

      this.$refs['upsellModal'].show();

      this.queryUpsell();
    },
    cancelUpsell() {
      this.$refs['upsellModal'].hide();
    },
    completeSale() {
      this.$refs['upsellModal'].hide();
      console.log(this.$refs['transactions'].total);

      this.pay(this.$refs['transactions'].total);
    },
    async queryUpsell() {
      const cartItems = this.lineItems.reduce((acc, curr) => {
        acc = [
          {
            itemId: curr.item.id,
            itemCount: curr.numberOfItems,
          },
          ...acc,
        ];
        return acc;
      }, []);

      const upsellRequest = {
        items: cartItems,
      };

      const response = await RetailEdgeAppApi.upsell(upsellRequest);
      const data = await response.json();

      console.log(data);
      console.log(this.items);

      if (data.upsellItems.length == 0) {
        // TODO(bfogel): procede directly to checkout
        this.notifyWarning('No upsell items found!');
        this.loadingUpsell = false;
        return;
      }
      console.log(data.upsellItems);

      for (const dataItem of data.upsellItems) {
        let upsellItem = {
          recipeSuggestion: dataItem.recipeSuggestion,
          name: dataItem.upsellItem.name,
          type: dataItem.upsellItem.type,
          added: false, // used for modal display
          matched: false, // used to match with this.items
        };

        for (const item of this.items) {
          if (upsellItem.name === item.name) {
            upsellItem = {...item, ...upsellItem};
            upsellItem.matched = true;
            break;
          }
        }

        if (upsellItem.matched) {
          this.upsellItems.push(upsellItem);
        } else {
          console.log(`no match for ${upsellItem.name}`);
        }
      }

      this.loadingUpsell = false;
    },
    async pay(total) {
      if (this.lineItems.length == 0) {
        this.notifyWarning('No items in cart!');
        return;
      }
      const cartItems = this.lineItems.reduce((acc, curr) => {
        acc = [
          {
            itemId: curr.item.id,
            itemCount: curr.numberOfItems,
          },
          ...acc,
        ];
        return acc;
      }, []);
      const payRequest = {
        paidAmount: total,
        type: 'CASH',
        items: cartItems,
      };
      const response = await RetailEdgeAppApi.pay(payRequest);
      if (response.status !== 200 && response.status !== 204) {
        this.notifyFailure('Payment API failed!');
        return;
      }
      const responseData = await response.json();
      if (responseData.status !== 'SUCCESS') {
        this.notifyFailure('Payment attempt failed!');
        return;
      }
      this.notifySuccess('Payment successful!');
      this.printedBill = responseData.printedBill;
      setTimeout(() => {
        this.clearBill();
      }, 20000);
    },
    clear() {
      this.lineItems = [];
      this.clearBill();
    },
    clearBill() {
      this.printedBill = null;
    },
    notifySuccess(message) {
      this.notify(message, 'success');
    },
    notifyWarning(message) {
      this.notify(message, 'default');
    },
    notifyFailure(message) {
      this.notify(message, 'error');
    },
    notify(message, type) {
      this.$toast.open({
        message,
        type,
        position: 'top',
        duration: 1500,
      });
    },
  },
};
</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped>
nav {
  margin-bottom: 28px;
}

.col-md-8 {
  max-width: 100%;
  flex: 100%;
}

.h1-flex {
  flex: 1;
}

.header-container {
  display: flex;
  margin-top: 18px;
  margin-bottom: 24px;
}

.bill-container {
  margin-top: 28px;
}

.print-container {
  background-color: beige;
  text-align: center;
  border-radius: 30px;
  padding-bottom: 20px;
  padding-top: 20px;
}

.find-form {
  padding-top: 10px;
}

.found-item {
  padding-top: 28px
}
</style>
