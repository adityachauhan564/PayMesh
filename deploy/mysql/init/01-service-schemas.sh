#!/bin/sh
# Runs once, when the mysql-data volume is first created: one schema and one user per service.
# Each user can only touch its own schema (Flyway needs DDL there, so ALL PRIVILEGES on it).
set -eu
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS user_db;
CREATE USER IF NOT EXISTS 'user_svc'@'%' IDENTIFIED BY '${USER_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON user_db.* TO 'user_svc'@'%';

CREATE DATABASE IF NOT EXISTS wallet_db;
CREATE USER IF NOT EXISTS 'wallet_svc'@'%' IDENTIFIED BY '${WALLET_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON wallet_db.* TO 'wallet_svc'@'%';

CREATE DATABASE IF NOT EXISTS txn_db;
CREATE USER IF NOT EXISTS 'txn_svc'@'%' IDENTIFIED BY '${TXN_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON txn_db.* TO 'txn_svc'@'%';
SQL
