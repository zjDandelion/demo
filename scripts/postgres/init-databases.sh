#!/usr/bin/env bash
set -euo pipefail

: "${NA_ORDER_DB_PASSWORD:?NA_ORDER_DB_PASSWORD is required}"
: "${NA_PAYMENT_DB_PASSWORD:?NA_PAYMENT_DB_PASSWORD is required}"

create_role() {
    local role="$1"
    local password="$2"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
        -v role_name="$role" -v role_password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role_name', :'role_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role_name') \gexec
SQL
}

create_database() {
    local db="$1"
    local owner="$2"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
        -v db_name="$db" -v db_owner="$owner" <<'SQL'
SELECT format('CREATE DATABASE %I OWNER %I', :'db_name', :'db_owner')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec
SQL
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
        -c "REVOKE ALL ON DATABASE \"$db\" FROM PUBLIC"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
        -c "GRANT CONNECT, TEMPORARY ON DATABASE \"$db\" TO \"$owner\""
}

create_role "na_order_user" "$NA_ORDER_DB_PASSWORD"
create_role "na_payment_user" "$NA_PAYMENT_DB_PASSWORD"

create_database "na_orderdb" "na_order_user"
create_database "na_paymentdb" "na_payment_user"
