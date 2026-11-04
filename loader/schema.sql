-- Olist Brazilian E-Commerce (https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce).
-- Column order follows the CSV files. UNIQUE KEY makes re-loading idempotent.

CREATE DATABASE IF NOT EXISTS olist;

CREATE TABLE IF NOT EXISTS olist.customers (
  customer_id              VARCHAR(32),
  customer_unique_id       VARCHAR(32),
  customer_zip_code_prefix INT,
  customer_city            VARCHAR(128),
  customer_state           VARCHAR(2)
) UNIQUE KEY(customer_id) DISTRIBUTED BY HASH(customer_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS olist.orders (
  order_id                      VARCHAR(32),
  customer_id                   VARCHAR(32),
  order_status                  VARCHAR(16),
  order_purchase_timestamp      DATETIME,
  order_approved_at             DATETIME,
  order_delivered_carrier_date  DATETIME,
  order_delivered_customer_date DATETIME,
  order_estimated_delivery_date DATETIME
) UNIQUE KEY(order_id) DISTRIBUTED BY HASH(order_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS olist.order_items (
  order_id            VARCHAR(32),
  order_item_id       INT,
  product_id          VARCHAR(32),
  seller_id           VARCHAR(32),
  shipping_limit_date DATETIME,
  price               DECIMAL(10, 2),
  freight_value       DECIMAL(10, 2)
) UNIQUE KEY(order_id, order_item_id) DISTRIBUTED BY HASH(order_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS olist.order_payments (
  order_id             VARCHAR(32),
  payment_sequential   INT,
  payment_type         VARCHAR(16),
  payment_installments INT,
  payment_value        DECIMAL(10, 2)
) UNIQUE KEY(order_id, payment_sequential) DISTRIBUTED BY HASH(order_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS olist.products (
  product_id                 VARCHAR(32),
  product_category_name      VARCHAR(128),
  product_name_length        INT,
  product_description_length INT,
  product_photos_qty         INT,
  product_weight_g           INT,
  product_length_cm          INT,
  product_height_cm          INT,
  product_width_cm           INT
) UNIQUE KEY(product_id) DISTRIBUTED BY HASH(product_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS olist.sellers (
  seller_id              VARCHAR(32),
  seller_zip_code_prefix INT,
  seller_city            VARCHAR(128),
  seller_state           VARCHAR(2)
) UNIQUE KEY(seller_id) DISTRIBUTED BY HASH(seller_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");
