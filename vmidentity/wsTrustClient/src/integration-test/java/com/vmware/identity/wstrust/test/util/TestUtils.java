/*
 *  Copyright (c) 2012-2018 VMware, Inc.  All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License.  You may obtain a copy
 *  of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, without
 *  warranties or conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.vmware.identity.wstrust.test.util;

import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.TrustStrategy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.identity.openidconnect.client.AccessToken;
import com.vmware.identity.openidconnect.client.ClientConfig;
import com.vmware.identity.openidconnect.client.OIDCClient;
import com.vmware.identity.openidconnect.client.OIDCClientException;
import com.vmware.identity.openidconnect.client.OIDCServerException;
import com.vmware.identity.openidconnect.client.OIDCTokens;
import com.vmware.identity.openidconnect.client.SSLConnectionException;
import com.vmware.identity.openidconnect.client.TokenSpec;
import com.vmware.identity.openidconnect.client.TokenValidationException;
import com.vmware.identity.openidconnect.common.Issuer;
import com.vmware.identity.openidconnect.common.JWTID;
import com.vmware.identity.openidconnect.common.TokenType;
import com.vmware.identity.openidconnect.protocol.Base64Utils;
import com.vmware.directory.rest.client.VmdirClient;
import com.vmware.identity.rest.afd.client.AfdClient;
import com.vmware.identity.rest.core.data.CertificateDTO;
import com.vmware.identity.rest.idm.client.IdmClient;

/**
 * Test Utils
 *
 * @author Jun Sun
 */
public class TestUtils {

  protected static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  public static final long CLOCK_TOLERANCE_IN_SECONDS = 5 * 60L; // 5 mins

  public static String buildBaseToken(Issuer issuer, String audience, String tokenClass, RSAPrivateKey rsaPrivateKey,
                                    long tokenLifeTime) throws Exception {

    Date now = new Date();
    Date issueTime = now;
    Date expirationTime = new Date(now.getTime() + tokenLifeTime);
    return buildBaseToken(issuer, audience, tokenClass, rsaPrivateKey, issueTime, expirationTime);
  }

  public static String buildBaseToken(
      Issuer issuer,
      String audience,
      String tokenClass,
      RSAPrivateKey rsaPrivateKey,
      Date issueTime,
      Date expirationTime) throws Exception {

    // build an id token
    JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder();
    claimsBuilder = claimsBuilder.subject("PersonUser");
    claimsBuilder = claimsBuilder.issuer(issuer.getValue());
    claimsBuilder = claimsBuilder.claim("token_type", TokenType.HOK.getValue());
    claimsBuilder = claimsBuilder.audience(audience);
    claimsBuilder = claimsBuilder.expirationTime(expirationTime);
    claimsBuilder = claimsBuilder.claim("token_class", tokenClass);
    claimsBuilder = claimsBuilder.jwtID(new JWTID().getValue());
    claimsBuilder = claimsBuilder.issueTime(issueTime);
    claimsBuilder = claimsBuilder.claim("scope", "openid");
    claimsBuilder = claimsBuilder.claim("tenant", "test_tenant");

    SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claimsBuilder.build());
    JWSSigner signer = new RSASSASigner(rsaPrivateKey);
    signedJWT.sign(signer);

    return signedJWT.serialize();
  }

  public static void populateSSLCertificates(
      String domainControllerFQDN,
      int domainControllerPort,
      KeyStore keyStore) throws Exception {
    AfdClient afdClient = new AfdClient(
        domainControllerFQDN,
        domainControllerPort,
        NoopHostnameVerifier.INSTANCE,
        new SSLContextBuilder().loadTrustMaterial(
            null,
            new TrustStrategy() {
              @Override
              public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                return true;
              }
            }).build());
    logger.info("Retrieving SSL Certificates from VECS endpoint");
    List<CertificateDTO> certs = afdClient.vecs().getSSLCertificates();
    int index = 1;
    for (CertificateDTO cert : certs) {
      keyStore.setCertificateEntry(String.format("VecsSSLCert%d", index), cert.getX509Certificate());
      index++;
    }
  }

  public static IdmClient createIdmClient(
      AccessToken accessToken,
      String domainControllerFQDN,
      int domainControllerPort,
      KeyStore keyStore,
      RSAPrivateKey privateKey) throws Exception {
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);
    SSLContext sslContext = SSLContext.getInstance("SSL");
    sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
    IdmClient idmClient = new IdmClient(
        domainControllerFQDN,
        domainControllerPort,
        new DefaultHostnameVerifier(),
        sslContext);
    com.vmware.identity.rest.core.client.AccessToken restAccessToken =
        new com.vmware.identity.rest.core.client.AccessToken(
            accessToken.getValue(),
            privateKey == null ?
                com.vmware.identity.rest.core.client.AccessToken.Type.JWT :
                com.vmware.identity.rest.core.client.AccessToken.Type.JWT_HOK,
            privateKey);
    idmClient.setToken(restAccessToken);
    return idmClient;
  }

  public static VmdirClient createVMdirClient(
      AccessToken accessToken,
      String domainControllerFQDN,
      int domainControllerPort,
      KeyStore keyStore) throws Exception {
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);
    SSLContext sslContext = SSLContext.getInstance("SSL");
    sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
    VmdirClient vmdirClient = new VmdirClient(
        domainControllerFQDN,
        domainControllerPort,
        new DefaultHostnameVerifier(),
        sslContext);
    com.vmware.identity.rest.core.client.AccessToken restAccessToken =
        new com.vmware.identity.rest.core.client.AccessToken(accessToken.getValue(),
            com.vmware.identity.rest.core.client.AccessToken.Type.JWT);
    vmdirClient.setToken(restAccessToken);
    return vmdirClient;
  }

  public static AccessToken getOidcBearerTokenWithUsernamePassword(ClientConfig clientConfig, String username, String
      password)
      throws OIDCClientException, OIDCServerException, SSLConnectionException, TokenValidationException {
    OIDCClient nonRegNoHOKConfigClient = new OIDCClient(clientConfig);
    TokenSpec tokenSpec = new TokenSpec.Builder().resourceServers(Arrays.asList("rs_admin_server")).build();
    OIDCTokens oidcTokens = nonRegNoHOKConfigClient.acquireTokensByPassword(username, password, tokenSpec);
    return oidcTokens.getAccessToken();
  }

  public static String convertToBase64PEMString(X509Certificate x509Certificate) throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    byteArrayOutputStream.write("-----BEGIN CERTIFICATE-----".getBytes());
    byteArrayOutputStream.write("\n".getBytes());
    byteArrayOutputStream.write(Base64Utils.encodeToBytes(x509Certificate.getEncoded()));
    byteArrayOutputStream.write("-----END CERTIFICATE-----".getBytes());
    byteArrayOutputStream.write("\n".getBytes());
    return byteArrayOutputStream.toString();
  }
}
