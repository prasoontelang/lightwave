/*
 * Copyright © 2018 VMware, Inc.  All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”) you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS, without
 * warranties or conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

#define LWCA_DB_LW_SERVER           "dbLwServer"
#define LWCA_DB_POST_SERVER         "dbPostServer"
#define LWCA_DB_DOMAIN              "dbDomain"

#define LWCA_POST_OBJ_CLASS             "objectClass"
#define LWCA_POST_CONTAINER             "container"
#define LWCA_POST_TOP                   "top"

// Certificate Authority LDAP Metadata
#define LWCA_POST_CONFIG_DN             "cn=Configuration,%s"
#define LWCA_POST_CA_CONFIG_DN          "cn=%s," LWCA_POST_CONFIG_DN
#define LWCA_POST_CA_DN                 "cn=Certificate-Authority,%s"
#define LWCA_POST_ROOT_CA_DN_ENTRY      "cn=%s," LWCA_POST_CA_DN
#define LWCA_POST_INTERMEDIATE_CA_DN_ENTRY   "cn=%s,%s"
#define LWCA_POST_CERTS_CN              "Certs"
#define LWCA_POST_ROOT_CERTS_DN         "cn=" LWCA_POST_CERTS_CN "," LWCA_POST_ROOT_CA_DN_ENTRY
#define LWCA_POST_INTR_CERTS_DN         "cn=" LWCA_POST_CERTS_CN "," LWCA_POST_INTERMEDIATE_CA_DN_ENTRY
#define LWCA_POST_CA_OBJ_CLASS          "vmwCertificationAuthority"
#define LWCA_POST_CN_FILTER             "(&(cn=%s)(objectClass=%s))"
#define LWCA_POST_PKICA_AUX_CLASS       "pkiCA"
#define LWCA_POST_CA_SUBJ_NAME          "cACertificateDN"
#define LWCA_POST_CA_ENCR_PRIV_KEY      "cAEncryptedPrivateKey"
#define LWCA_POST_CA_STATUS             "cAStatus"
#define LWCA_POST_CA_CRL_NUM            "cACRLNumber"
#define LWCA_POST_CA_PARENT             "cAParentCAId"
#define LWCA_POST_CA_CERTIFICATES       "cACertificate"
#define LWCA_POST_CA_LAST_CRL_UPDATE    "cALastCRLUpdate"
#define LWCA_POST_CA_NEXT_CRL_UPDATE    "cANextCRLUpdate"
#define LWCA_POST_CA_AUTH_BLOB          "cAAuthBlob"

// Certificate Data LDAP Metadata
#define LWCA_POST_CERT_DN               "cn=%s,cn=" LWCA_POST_CERTS_CN ",%s"
#define LWCA_POST_CERT_OBJ_CLASS        "vmwCerts"
#define LWCA_POST_CERT_SERIAL_NUM       "certSerialNumber"
#define LWCA_POST_CERT_ISSUER           "certIssuer"
#define LWCA_POST_CERT_REVOKED_REASON   "certRevokedReason"
#define LWCA_POST_CERT_REVOKED_DATE     "certRevokedDate"
#define LWCA_POST_CERT_TIME_VALID_FROM  "certTimeValidFrom"
#define LWCA_POST_CERT_TIME_VALID_TO    "certTimeValidTo"
#define LWCA_POST_CERT_STATUS           "certStatus"
#define LWCA_POST_CERT_OBJ_FILTER       "(objectClass="LWCA_POST_CERT_OBJ_CLASS")"

// Lock attributes on POST
#define LWCA_POST_LOCK_OBJ_CLASS        "resourceLock"
#define LWCA_POST_ATTR_LOCK_OWNER       "resourceLockOwner"
#define LWCA_POST_ATTR_LOCK_EXPIRE      "resourceLockExpirationTime"
#define LWCA_POST_ATTR_LOCK_TAGS        "resourceTags"
#define LWCA_LOCK_MAX_DRIFT             60
#define LWCA_LOCK_MAX_ATTEMPT           1000
#define LWCA_LOCK_SLEEP_NS              (500 * 1000000L)
#define LWCA_LOCK_UNOWNED               "noOwner"
#define LWCA_LOCK_UNOWNED_EXPIRE_TIME   0
#define LWCA_LOCK_CONDITION             ("(|"\
                                         "(!(" LWCA_POST_ATTR_LOCK_OWNER "=*))" \
                                         "(" LWCA_POST_ATTR_LOCK_OWNER "=" LWCA_LOCK_UNOWNED ")" \
                                         "(" LWCA_POST_ATTR_LOCK_OWNER "=%s)" \
                                         "(" LWCA_POST_ATTR_LOCK_EXPIRE "<=%d)" \
                                         ")")

#define LWCA_POST_JSON_ATTR             "attributes"

#define LWCA_EXPIRATION_BUFFER_TIME     (3 * 60)
#define LWCA_POST_REST_PORT             7578
#define LWCA_POST_REST_HTTPS            "https"
#define LWCA_POST_REST_IF_MATCH         "If-Match"
#define LWCA_POST_URI_PREFIX            "/v1/post/ldap"
#define LWCA_RESP_RESULT_COUNT          "result_count"
#define LWCA_RESP_RESULT                "result"
#define LWCA_LDAP_DN                    "dn"
#define LWCA_LDAP_CN                    "cn"
#define LWCA_LDAP_SCOPE                 "scope"
#define LWCA_LDAP_ATTRS                 "attrs"
#define LWCA_LDAP_SCOPE_SUB             "sub"
#define LWCA_LDAP_FILTER                "filter"
#define LWCA_LDAP_ATTR_TYPE             "type"
#define LWCA_LDAP_ATTR_VALUE            "value"
#define LWCA_LDAP_REPLACE               "replace"
#define LWCA_LDAP_OPERATION             "operation"
#define LWCA_LDAP_UPDATE_ATTR           "attribute"

#define LWCA_PARAM_DELIM(cond)          (cond?"&":"?")

#define LWCA_HTTP_OK                    200
#define LWCA_HTTP_NOT_FOUND             404
#define LWCA_HTTP_CONFLICT              409
#define LWCA_HTTP_LOCK_HELD             412
#define LWCA_POST_OIDC_SCOPE            "openid id_groups at_groups rs_post"

#define LWCA_SAFE_FREE_CURL_MEMORY(PTR)   \
    do {                                  \
        if ((PTR)) {                      \
            curl_free(PTR);               \
            (PTR) = NULL;                 \
        }                                 \
    } while(0)

