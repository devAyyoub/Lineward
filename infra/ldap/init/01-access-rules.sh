#!/bin/bash
#
# Creates the read only account Lineward reads this directory with, and the
# access rules without which the directory hands its password hashes to anybody
# who asks.
#
# Why this file exists at all, measured on slapd 2.6.10 on 2026-11-03: the
# database this image creates has NO access rules of its own, and slapd's
# fallback when a database declares none is to let everybody read everything. An
# anonymous client, with no bind and no credential, was served the userPassword
# attribute of every account in the tree. That is not a weakness of OpenLDAP, it
# is a directory that nobody told what to protect.
#
# So the rules below are part of the delivery, exactly like the realm file of the
# Keycloak connector. There are three, in the order slapd evaluates them, which
# is first match wins:
#
#   0. userPassword   nobody reads it. The owner of the entry may change it, and
#                     a bind may check it, which is what "auth" grants. No
#                     reader, Lineward included, ever receives the value.
#   1. the subtree    the aggregator reads it. Everybody else, including an
#                     anonymous client, gets nothing.
#   2. everything     denied, because a rule that is never reached is a rule
#                     nobody can be sure about.
#
# The admin account is the rootdn of the database and bypasses access rules by
# definition, which is why it is not named here and why it keeps working.
#
# What the aggregator deliberately cannot do, and each of these is proved by a
# test that watches it fail: write any entry, read a password, or see the
# configuration database. Reading accounts needs none of the three, and this
# component is the one that holds the credentials of every target system.
#
# EVERYTHING HERE IS DONE OFFLINE, and that is the one thing to know before
# editing this file. The image runs these hooks after initialisation has
# finished, which means it has already STOPPED slapd: there is no server and no
# socket, so ldapadd and ldapmodify fail with "Can't contact LDAP server (-1)".
# Verified on 2026-11-03. The offline tools write the same databases directly,
# which is also why the index has to be built by hand at the end.
#
# This script runs once, on an empty data directory, after the tree in
# ../import has been loaded. To force it again: docker compose down -v
set -euo pipefail

: "${LDAP_ROOT:=dc=lineward,dc=example}"
: "${LDAP_ONLINE_CONF_DIR:?the configuration directory of the running server is not set}"
: "${LINEWARD_LDAP_READER_DN:=cn=lineward-aggregator,ou=services,${LDAP_ROOT}}"
: "${LINEWARD_LDAP_READER_PASSWORD:?the directory will not be configured without a password for the reader}"

echo "Creating the Lineward read only account and the access rules"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# The password reaches the database hashed, so what is stored is not what is in
# the environment, and the environment is the only place the plain value exists.
cat > "${work}/reader.ldif" <<LDIF
dn: ou=services,${LDAP_ROOT}
objectClass: organizationalUnit
ou: services

dn: ${LINEWARD_LDAP_READER_DN}
objectClass: applicationProcess
objectClass: simpleSecurityObject
cn: lineward-aggregator
description: Read only account Lineward aggregates this directory with.
userPassword: $(slappasswd -s "${LINEWARD_LDAP_READER_PASSWORD}")
LDIF

slapadd -F "${LDAP_ONLINE_CONF_DIR}" -b "${LDAP_ROOT}" -l "${work}/reader.ldif" -q

# The database is addressed by its suffix and not by its number. The entry is
# called olcDatabase={2}mdb today, and that number is an ordinal that moves the
# day another database is declared before it.
database_dn="$(slapcat -F "${LDAP_ONLINE_CONF_DIR}" -n 0 2>/dev/null \
    | awk -v root="${LDAP_ROOT}" '
        /^dn: olcDatabase=/ { dn = substr($0, 5) }
        $0 == "olcSuffix: " root { print dn }')"

if [ -z "${database_dn}" ]; then
    echo "no database found with suffix ${LDAP_ROOT}, refusing to continue" >&2
    exit 1
fi

cat > "${work}/access.ldif" <<LDIF
dn: ${database_dn}
changetype: modify
add: olcAccess
olcAccess: {0}to attrs=userPassword
  by self write
  by anonymous auth
  by * none
olcAccess: {1}to dn.subtree="${LDAP_ROOT}"
  by dn.exact="${LINEWARD_LDAP_READER_DN}" read
  by self read
  by * none
olcAccess: {2}to *
  by * none
-
add: olcDbIndex
olcDbIndex: member eq
-
add: olcDbIndex
olcDbIndex: uid eq
-
add: olcDbIndex
olcDbIndex: entryUUID eq
LDIF

slapmodify -F "${LDAP_ONLINE_CONF_DIR}" -n 0 -l "${work}/access.ldif" -q

# Declaring an index does not build it. The entries were loaded before these
# three indexes existed, so without this the directory would answer a search on
# member with an index that knows about none of them, and an incomplete answer
# to "which groups hold this account" reads as an account with no access at all.
slapindex -F "${LDAP_ONLINE_CONF_DIR}" -b "${LDAP_ROOT}" -q

echo "Access rules applied to ${database_dn}"
