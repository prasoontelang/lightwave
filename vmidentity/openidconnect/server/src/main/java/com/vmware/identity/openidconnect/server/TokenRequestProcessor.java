/*
 *  Copyright (c) 2012-2015 VMware, Inc.  All Rights Reserved.
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

package com.vmware.identity.openidconnect.server;

import static com.vmware.identity.openidconnect.server.FederatedIdentityProvider.getPrincipalId;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.nimbusds.jose.JOSEException;
import com.vmware.identity.diagnostics.MetricUtils;
import com.vmware.identity.diagnostics.DiagnosticsLoggerFactory;
import com.vmware.identity.diagnostics.IDiagnosticsLogger;
import com.vmware.identity.idm.GSSResult;
import com.vmware.identity.idm.IDMSecureIDNewPinException;
import com.vmware.identity.idm.PrincipalId;
import com.vmware.identity.idm.RSAAMResult;
import com.vmware.identity.idm.client.CasIdmClient;
import com.vmware.identity.openidconnect.common.AuthorizationCode;
import com.vmware.identity.openidconnect.common.ClientID;
import com.vmware.identity.openidconnect.common.ErrorObject;
import com.vmware.identity.openidconnect.common.GrantType;
import com.vmware.identity.openidconnect.common.Nonce;
import com.vmware.identity.openidconnect.common.ParseException;
import com.vmware.identity.openidconnect.common.Scope;
import com.vmware.identity.openidconnect.common.ScopeValue;
import com.vmware.identity.openidconnect.common.SessionID;
import com.vmware.identity.openidconnect.protocol.AccessToken;
import com.vmware.identity.openidconnect.protocol.AuthorizationCodeGrant;
import com.vmware.identity.openidconnect.protocol.Base64Utils;
import com.vmware.identity.openidconnect.protocol.ClientCredentialsGrant;
import com.vmware.identity.openidconnect.protocol.FederationIDPIssuerType;
import com.vmware.identity.openidconnect.protocol.FederationToken;
import com.vmware.identity.openidconnect.protocol.FederationTokenGrant;
import com.vmware.identity.openidconnect.protocol.GSSTicketGrant;
import com.vmware.identity.openidconnect.protocol.HttpRequest;
import com.vmware.identity.openidconnect.protocol.HttpResponse;
import com.vmware.identity.openidconnect.protocol.IDToken;
import com.vmware.identity.openidconnect.protocol.PasswordGrant;
import com.vmware.identity.openidconnect.protocol.PersonUserAssertion;
import com.vmware.identity.openidconnect.protocol.PersonUserCertificateGrant;
import com.vmware.identity.openidconnect.protocol.RefreshToken;
import com.vmware.identity.openidconnect.protocol.RefreshTokenGrant;
import com.vmware.identity.openidconnect.protocol.SecurIDGrant;
import com.vmware.identity.openidconnect.protocol.SolutionUserCredentialsGrant;
import com.vmware.identity.openidconnect.protocol.TokenErrorResponse;
import com.vmware.identity.openidconnect.protocol.TokenRequest;
import com.vmware.identity.openidconnect.protocol.TokenSuccessResponse;

import io.prometheus.client.Histogram.Timer;

/**
 * @author Yehia Zayour
 */
public class TokenRequestProcessor {
    private static final long ASSERTION_LIFETIME_MS = 1 * 60 * 1000L; // 1 minute

    private static final IDiagnosticsLogger logger = DiagnosticsLoggerFactory.getLogger(TokenRequestProcessor.class);

    private final TenantInfoRetriever tenantInfoRetriever;
    private final ClientInfoRetriever clientInfoRetriever;
    private final ServerInfoRetriever serverInfoRetriever;
    private final UserInfoRetriever userInfoRetriever;
    private final PersonUserAuthenticator personUserAuthenticator;
    private final SolutionUserAuthenticator solutionUserAuthenticator;
    private final FederatedIdentityProviderInfoRetriever federatedInfoRetriever;
    private final FederatedIdentityProvider federatedIdentityProvider;

    // in memory cache to store public key per issuer
    private final HashMap<String, FederatedTokenPublicKey> publicKeyLookup;
    private final ReadWriteLock keyLookupLock;
    private final Lock readLockKeyLookup;
    private final Lock writeLockKeyLookup;

    private final AuthorizationCodeManager authzCodeManager;
    private final HttpRequest httpRequest;
    private String tenant;

    private TenantInfo tenantInfo;
    private TokenRequest tokenRequest;

    public TokenRequestProcessor(
            CasIdmClient idmClient,
            AuthorizationCodeManager authzCodeManager,
            HttpRequest httpRequest,
            String tenant) throws ServerException {
        this.tenantInfoRetriever = new TenantInfoRetriever(idmClient);
        this.clientInfoRetriever = new ClientInfoRetriever(idmClient);
        this.serverInfoRetriever = new ServerInfoRetriever(idmClient);
        this.userInfoRetriever = new UserInfoRetriever(idmClient);
        this.personUserAuthenticator = new PersonUserAuthenticator(idmClient);
        this.solutionUserAuthenticator = new SolutionUserAuthenticator(idmClient);

        this.authzCodeManager = authzCodeManager;
        this.httpRequest = httpRequest;
        this.tenant = tenant;
        if (this.tenant == null) {
            this.tenant = this.tenantInfoRetriever.getDefaultTenantName();
        }
        this.tenantInfo = null;
        this.tokenRequest = null;
        this.federatedIdentityProvider = new FederatedIdentityProvider(this.tenant, idmClient);
        this.federatedInfoRetriever = new FederatedIdentityProviderInfoRetriever(idmClient);

        publicKeyLookup = new HashMap<String, FederatedTokenPublicKey>();
        keyLookupLock = new ReentrantReadWriteLock();
        readLockKeyLookup = keyLookupLock.readLock();
        writeLockKeyLookup = keyLookupLock.writeLock();
    }

    public HttpResponse process() {
        try {
            this.tokenRequest = TokenRequest.parse(this.httpRequest);
        } catch (ParseException e) {
            LoggerUtils.logFailedRequest(logger, e.getErrorObject(), e);
            TokenErrorResponse tokenErrorResponse = new TokenErrorResponse(e.getErrorObject());
            return tokenErrorResponse.toHttpResponse();
        }

        try {
            this.tenantInfo = this.tenantInfoRetriever.retrieveTenantInfo(this.tenant);
            this.tenant = this.tenantInfo.getName(); // use tenant name as it appears in directory

            TokenSuccessResponse tokenSuccessResponse = processInternal();
            HttpResponse httpResponse = tokenSuccessResponse.toHttpResponse();
            logger.info(
                    "subject [{}] grant_type [{}]",
                    tokenSuccessResponse.getIDToken().getSubject().getValue(),
                    this.tokenRequest.getAuthorizationGrant().getGrantType().getValue());
            return httpResponse;
        } catch (ServerException e) {
            LoggerUtils.logFailedRequest(logger, e);
            TokenErrorResponse tokenErrorResponse = new TokenErrorResponse(e.getErrorObject());
            return tokenErrorResponse.toHttpResponse();
        }
    }

    private TokenSuccessResponse processInternal() throws ServerException {
        GrantType grantType = this.tokenRequest.getAuthorizationGrant().getGrantType();

        SolutionUser solutionUser = null;
        if (this.tokenRequest.getClientID() != null) {
            ClientInfo clientInfo = this.clientInfoRetriever.retrieveClientInfo(this.tenant, this.tokenRequest.getClientID());
            boolean certRegistered = clientInfo.getCertSubjectDN() != null;
            if (certRegistered && this.tokenRequest.getClientAssertion() != null) {
                solutionUser = this.solutionUserAuthenticator.authenticateByClientAssertion(
                        this.tokenRequest.getClientAssertion(),
                        ASSERTION_LIFETIME_MS,
                        this.httpRequest.getURI(),
                        this.tenantInfo,
                        clientInfo);
            } else if (certRegistered && this.tokenRequest.getClientAssertion() == null) {
                throw new ServerException(ErrorObject.invalidClient("client_assertion parameter is required since client has registered a cert"));
            } else if (!certRegistered && this.tokenRequest.getClientAssertion() != null) {
                throw new ServerException(ErrorObject.invalidClient("client_assertion parameter is not allowed since client did not register a cert"));
            }
        }
        if (this.tokenRequest.getSolutionUserAssertion() != null) {
            solutionUser = this.solutionUserAuthenticator.authenticateBySolutionUserAssertion(
                    this.tokenRequest.getSolutionUserAssertion(),
                    ASSERTION_LIFETIME_MS,
                    this.httpRequest.getURI(),
                    this.tenantInfo);
        }

        TokenSuccessResponse tokenSuccessResponse;
        switch (grantType) {
            case AUTHORIZATION_CODE:
                tokenSuccessResponse = processAuthzCodeGrant(solutionUser);
                break;
            case PASSWORD:
                tokenSuccessResponse = processPasswordGrant(solutionUser);
                break;
            case SOLUTION_USER_CREDENTIALS:
                tokenSuccessResponse = processSolutionUserCredentialsGrant(solutionUser);
                break;
            case CLIENT_CREDENTIALS:
                tokenSuccessResponse = processClientCredentialsGrant(solutionUser);
                break;
            case PERSON_USER_CERTIFICATE:
                tokenSuccessResponse = processPersonUserCertificateGrant(solutionUser);
                break;
            case GSS_TICKET:
                tokenSuccessResponse = processGssTicketGrant(solutionUser);
                break;
            case SECURID:
                tokenSuccessResponse = processSecurIDGrant(solutionUser);
                break;
            case REFRESH_TOKEN:
                tokenSuccessResponse = processRefreshTokenGrant(solutionUser);
                break;
            case FEDERATION_TOKEN:
                tokenSuccessResponse = processFederatedTokenGrant(solutionUser);
                break;
            default:
                throw new IllegalStateException("unrecognized grant type: " + grantType.getValue());
        }
        return tokenSuccessResponse;
    }

    private TokenSuccessResponse processAuthzCodeGrant(SolutionUser solutionUser) throws ServerException {
        AuthorizationCodeGrant authzCodeGrant = (AuthorizationCodeGrant) this.tokenRequest.getAuthorizationGrant();
        AuthorizationCode authzCode = authzCodeGrant.getAuthorizationCode();
        URI redirectUri = authzCodeGrant.getRedirectURI();

        AuthorizationCodeManager.Entry entry = this.authzCodeManager.remove(authzCode);
        validateAuthzCode(entry, redirectUri);

        return process(
                entry.getPersonUser(),
                solutionUser,
                entry.getAuthenticationRequest().getClientID(),
                entry.getAuthenticationRequest().getScope(),
                entry.getAuthenticationRequest().getNonce(),
                entry.getSessionId(),
                true /* refreshTokenAllowed */,
                GrantType.AUTHORIZATION_CODE.toString());
    }

    private TokenSuccessResponse processPasswordGrant(SolutionUser solutionUser) throws ServerException {
        PasswordGrant passwordGrant = (PasswordGrant) this.tokenRequest.getAuthorizationGrant();
        String username = passwordGrant.getUsername();
        String password = passwordGrant.getPassword();

        PersonUser personUser;
        try {
            personUser = this.personUserAuthenticator.authenticateByPassword(this.tenant, username, password);
        } catch (InvalidCredentialsException e) {
            throw new ServerException(ErrorObject.invalidGrant("incorrect username or password"), e);
        }

        return process(
                personUser,
                solutionUser,
                this.tokenRequest.getClientID(),
                this.tokenRequest.getScope(),
                (Nonce) null,
                (SessionID) null,
                true /* refreshTokenAllowed */,
                GrantType.PASSWORD.toString());
    }

    private TokenSuccessResponse processSolutionUserCredentialsGrant(SolutionUser solutionUser) throws ServerException {
        assert this.tokenRequest.getAuthorizationGrant() instanceof SolutionUserCredentialsGrant;
        return process(
                (PersonUser) null,
                solutionUser,
                this.tokenRequest.getClientID(),
                this.tokenRequest.getScope(),
                (Nonce) null,
                (SessionID) null,
                false /* refreshTokenAllowed */,
                GrantType.SOLUTION_USER_CREDENTIALS.toString());
    }

    private TokenSuccessResponse processClientCredentialsGrant(SolutionUser solutionUser) throws ServerException {
        assert this.tokenRequest.getAuthorizationGrant() instanceof ClientCredentialsGrant;
        return process(
                (PersonUser) null,
                solutionUser,
                this.tokenRequest.getClientID(),
                this.tokenRequest.getScope(),
                (Nonce) null,
                (SessionID) null,
                false /* refreshTokenAllowed */,
                GrantType.CLIENT_CREDENTIALS.toString());
    }

    private TokenSuccessResponse processPersonUserCertificateGrant(SolutionUser solutionUser) throws ServerException {
        PersonUserCertificateGrant personUserCertificateGrant = (PersonUserCertificateGrant) this.tokenRequest.getAuthorizationGrant();
        X509Certificate personUserCertificate = personUserCertificateGrant.getPersonUserCertificate();
        PersonUserAssertion personUserAssertion = personUserCertificateGrant.getPersonUserAssertion();

        PersonUser personUser;
        try {
            personUser = this.personUserAuthenticator.authenticateByPersonUserCertificate(
                    this.tenant,
                    personUserCertificate,
                    personUserAssertion,
                    ASSERTION_LIFETIME_MS,
                    this.httpRequest.getURI(),
                    this.tenantInfo.getClockToleranceMs());
        } catch (InvalidCredentialsException e) {
            throw new ServerException(ErrorObject.invalidGrant("invalid person user cert"), e);
        }

        return process(
                personUser,
                solutionUser,
                this.tokenRequest.getClientID(),
                this.tokenRequest.getScope(),
                (Nonce) null,
                (SessionID) null,
                true /* refreshTokenAllowed */,
                GrantType.PERSON_USER_CERTIFICATE.toString());
    }

    private TokenSuccessResponse processGssTicketGrant(SolutionUser solutionUser) throws ServerException {
        GSSTicketGrant gssTicketGrant = (GSSTicketGrant) this.tokenRequest.getAuthorizationGrant();
        byte[] gssTicket = gssTicketGrant.getGSSTicket();
        String contextId = gssTicketGrant.getContextID();

        GSSResult result;
        try {
            result = this.personUserAuthenticator.authenticateByGssTicket(this.tenant, contextId, gssTicket);
        } catch (InvalidCredentialsException e) {
            throw new ServerException(ErrorObject.invalidGrant("invalid gss ticket"), e);
        }

        PersonUser personUser;
        if (result.complete()) {
            personUser = new PersonUser(result.getPrincipalId(), this.tenant);
        } else {
            String base64OfServerLeg = Base64Utils.encodeToString(result.getServerLeg());
            String message = String.format("gss_continue_needed:%s:%s", contextId, base64OfServerLeg);
            throw new ServerException(ErrorObject.invalidGrant(message));
        }

        return process(
                personUser,
                solutionUser,
                this.tokenRequest.getClientID(),
                this.tokenRequest.getScope(),
                (Nonce) null,
                (SessionID) null,
                true /* refreshTokenAllowed */,
                GrantType.GSS_TICKET.toString());
    }

    private TokenSuccessResponse processSecurIDGrant(SolutionUser solutionUser) throws ServerException {
        SecurIDGrant securIdGrant = (SecurIDGrant) this.tokenRequest.getAuthorizationGrant();
        String username = securIdGrant.getUsername();
        String passcode = securIdGrant.getPasscode();
        String sessionId = securIdGrant.getSessionID();

        RSAAMResult result;
        try {
            result = this.personUserAuthenticator.authenticateBySecurID(this.tenant, username, passcode, sessionId);
        } catch (InvalidCredentialsException e) {
            throw new ServerException(ErrorObject.invalidGrant("incorrect securid username or passcode"));
        } catch (IDMSecureIDNewPinException e) {
            throw new ServerException(ErrorObject.invalidGrant("new securid pin required"));
        }

        PersonUser personUser;
        if (result.complete()) {
            personUser = new PersonUser(result.getPrincipalId(), this.tenant);
        } else {
            String base64OfSessionId = Base64Utils.encodeToString(result.getRsaSessionID());
            String message = String.format("securid_next_code_required:%s", base64OfSessionId);
            throw new ServerException(ErrorObject.invalidGrant(message));
        }

        return process(
                personUser,
                solutionUser,
                this.tokenRequest.getClientID(),
                this.tokenRequest.getScope(),
                (Nonce) null,
                (SessionID) null,
                true /* refreshTokenAllowed */,
                GrantType.SECURID.toString());
    }

    private TokenSuccessResponse processRefreshTokenGrant(SolutionUser solutionUser) throws ServerException {
        RefreshTokenGrant refreshTokenGrant = (RefreshTokenGrant) this.tokenRequest.getAuthorizationGrant();
        RefreshToken refreshToken = refreshTokenGrant.getRefreshToken();

        validateRefreshToken(refreshToken, solutionUser);

        PersonUser personUser;
        try {
            personUser = PersonUser.parse(refreshToken.getSubject().getValue(), this.tenant);
        } catch (ParseException e) {
            throw new ServerException(ErrorObject.invalidGrant("failed to parse subject into a PersonUser"), e);
        }

        return process(
                personUser,
                solutionUser,
                refreshToken.getClientID(),
                refreshToken.getScope(),
                refreshToken.getNonce(),
                refreshToken.getSessionID(),
                false /* refreshTokenAllowed */,
                GrantType.REFRESH_TOKEN.toString());
    }

    private TokenSuccessResponse processFederatedTokenGrant(SolutionUser solutionUser) throws ServerException {
        FederationTokenGrant federationTokenGrant = (FederationTokenGrant)this.tokenRequest.getAuthorizationGrant();
        FederationToken federationToken = federationTokenGrant.getFederationToken();
        String issuer = this.tenantInfoRetriever.getTenantIssuer(this.tenant);
        if (issuer == null || issuer.isEmpty()) {
            throw new ServerException(ErrorObject.invalidGrant("federation IDP is not registered with tenant " + this.tenant));
        }

        FederatedIdentityProviderInfo federatedIdpInfo;
        try {
            federatedIdpInfo = this.federatedInfoRetriever.retrieveInfo(this.tenant, issuer);
        } catch (Exception e1) {
            throw new ServerException(ErrorObject.serverError("failed to retrieve federated idp info with issuer " + issuer));
        }
        validateFederationToken(issuer, federationToken, solutionUser, federatedIdpInfo);
        federatedIdentityProvider.validateUserPermissions(federationToken.getPermissions(), federatedIdpInfo.getRoleGroupMappings().keySet());

        PersonUser personUser;
        PrincipalId userId = getPrincipalId(federationToken.getUsername(), federationToken.getDomain());
        try {
            if (!federatedIdentityProvider.isFederationUserActive(userId)) {
                federatedIdentityProvider.provisionFederationUser(issuer, userId);
            }
            federatedIdentityProvider.updateUserGroups(userId, federationToken.getPermissions(), federatedIdpInfo.getRoleGroupMappings());
            personUser = new PersonUser(userId, this.tenant);
        } catch (Exception e) {
            throw new ServerException(
                    ErrorObject.invalidGrant(String.format("failed to provision federation subject %s for tenant %s",
                            userId.getUPN(), this.tenant)), e);
        }

        TokenSuccessResponse response = process(
                personUser,
                solutionUser,
                this.tokenRequest.getClientID(),
                this.tokenRequest.getScope(),
                (Nonce)null,
                (SessionID)(null),
                false /* refreshTokenAllowed */,
                GrantType.FEDERATION_TOKEN.toString());

        String clientId = (this.tokenRequest.getClientID() == null) ? null : this.tokenRequest.getClientID().getValue();
        MetricUtils.recordFederatedTokenLogin(tenant, clientId);
        return response;
    }

    private TokenSuccessResponse process(
            PersonUser personUser,
            SolutionUser solutionUser,
            ClientID clientId,
            Scope scope,
            Nonce nonce,
            SessionID sessionId,
            boolean refreshTokenAllowed,
            String grantType) throws ServerException {
        User user = (personUser != null) ? personUser : solutionUser;
        Timer rsInfoTimer = MetricUtils.startRequestTimer(TokenController.metricsResource, "retrieveResourceServerInfo_" + grantType);
        Set<ResourceServerInfo> resourceServerInfos;
        try {
            resourceServerInfos = this.serverInfoRetriever.retrieveResourceServerInfos(this.tenant, scope);
        } finally {
            if (rsInfoTimer != null) {
                rsInfoTimer.observeDuration();
            }
        }

        Timer userInfoTimer = MetricUtils.startRequestTimer(TokenController.metricsResource, "retrieveUserInfo_" + grantType);
        UserInfo userInfo;
        try {
            userInfo = this.userInfoRetriever.retrieveUserInfo(user, scope, resourceServerInfos);
        } finally {
            if (userInfoTimer != null) {
                userInfoTimer.observeDuration();
            }
        }

        if (personUser != null && solutionUser != null) {
            boolean isMemberOfActAsGroup = this.userInfoRetriever.isMemberOfGroup(solutionUser, "ActAsUsers");
            if (!isMemberOfActAsGroup) {
                throw new ServerException(ErrorObject.accessDenied("solution user acting as a person user must be a member of ActAsUsers group"));
            }
        }

        TokenIssuer tokenIssuer = new TokenIssuer(
                personUser,
                solutionUser,
                userInfo,
                this.tenantInfo,
                scope,
                nonce,
                clientId,
                sessionId);

        IDToken idToken = tokenIssuer.issueIDToken();
        AccessToken accessToken = tokenIssuer.issueAccessToken();
        RefreshToken refreshToken = null;
        if (refreshTokenAllowed && scope.contains(ScopeValue.OFFLINE_ACCESS)) {
            refreshToken = tokenIssuer.issueRefreshToken();
        }

        return new TokenSuccessResponse(idToken, accessToken, refreshToken);
    }

    private void validateAuthzCode(AuthorizationCodeManager.Entry entry, URI tokenRequestRedirectUri) throws ServerException {
        if (entry == null) {
            throw new ServerException(ErrorObject.invalidGrant("invalid authorization code"));
        }

        if (!Objects.equals(entry.getAuthenticationRequest().getClientID(), this.tokenRequest.getClientID())) {
            throw new ServerException(ErrorObject.invalidGrant("client_id does not match that of the original authn request"));
        }

        if (!Objects.equals(entry.getAuthenticationRequest().getRedirectURI(), tokenRequestRedirectUri)) {
            throw new ServerException(ErrorObject.invalidGrant("redirect_uri does not match that of the original authn request"));
        }

        if (!Objects.equals(entry.getPersonUser().getTenant(), this.tenant)) {
            throw new ServerException(ErrorObject.invalidGrant("tenant does not match that of the original authn request"));
        }
    }

    private void validateRefreshToken(RefreshToken refreshToken, SolutionUser solutionUser) throws ServerException {
        try {
            if (!refreshToken.hasValidSignature(this.tenantInfo.getPublicKey())) {
                throw new ServerException(ErrorObject.invalidGrant("refresh_token has an invalid signature"));
            }
        } catch (JOSEException e) {
            throw new ServerException(ErrorObject.serverError("error while verifying refresh_token signature"), e);
        }

        if (!Objects.equals(refreshToken.getTenant(), this.tenant)) {
            throw new ServerException(ErrorObject.invalidGrant("refresh_token was not issued to this tenant"));
        }

        if (!Objects.equals(refreshToken.getClientID(), this.tokenRequest.getClientID())) {
            throw new ServerException(ErrorObject.invalidGrant("refresh_token was not issued to this client"));
        }

        if (!Objects.equals(refreshToken.getActAs(), (solutionUser == null) ? null : solutionUser.getSubject())) {
            throw new ServerException(ErrorObject.invalidGrant("refresh_token was not issued to this solution user"));
        }

        Date now = new Date();
        Date notBefore = new Date(refreshToken.getIssueTime().getTime() - this.tenantInfo.getClockToleranceMs());
        Date notAfter = new Date(refreshToken.getExpirationTime().getTime() + this.tenantInfo.getClockToleranceMs());
        if (now.before(notBefore)) {
            throw new ServerException(ErrorObject.invalidGrant("refresh_token is not yet valid"));
        }
        if (now.after(notAfter)) {
            throw new ServerException(ErrorObject.invalidGrant("refresh_token has expired"));
        }
    }

    private void validateFederationToken(String issuer, FederationToken token, SolutionUser solutionUser,
            FederatedIdentityProviderInfo federatedIdpInfo) throws ServerException {
        if (!this.tenant.equalsIgnoreCase(token.getTenant())) {
            throw new ServerException(ErrorObject.invalidGrant("Tenant of the request does not match the tenant in the federation token."));
        }
        try {
            RSAPublicKey key = getFederatedTokenPublicKey(issuer, federatedIdpInfo).getPublicKey();
            if (!token.hasValidSignature(key)) {
                throw new ServerException(ErrorObject.invalidGrant("federation token has an invalid signature"));
            }
        } catch (JOSEException e) {
            throw new ServerException(ErrorObject.serverError("error while verifying federation token signature"), e);
        }

        Date now = new Date();
        Date notBefore = new Date(token.getIssueTime().getTime() - this.tenantInfo.getClockToleranceMs());
        Date notAfter = new Date(token.getExpirationTime().getTime() + this.tenantInfo.getClockToleranceMs());
        if (now.before(notBefore)) {
            throw new ServerException(ErrorObject.invalidGrant("federation token is not yet valid"));
        }
        if (now.after(notAfter)) {
            throw new ServerException(ErrorObject.invalidGrant("federation token has expired"));
        }
    }

    private FederatedTokenPublicKey getFederatedTokenPublicKey(String issuer,
            FederatedIdentityProviderInfo federatedIdpInfo) throws ServerException {
        FederatedTokenPublicKey key = null;
        readLockKeyLookup.lock();
        try {
            key = publicKeyLookup.get(issuer);
        } finally {
            readLockKeyLookup.unlock();
        }

        if (key == null) {
            try {
                key = getPublicKey(federatedIdpInfo);
                writeKey(issuer, key);
            } catch (Exception e) {
                throw new ServerException(ErrorObject.serverError("error while retrieving federated idp public key"), e);
            }
        }

        return key;
    }

    private FederatedTokenPublicKey getPublicKey(FederatedIdentityProviderInfo federatedIdpInfo) throws Exception {
        String publicKeyURL = federatedIdpInfo.getJwkURI();
        return FederatedTokenPublicKeyFactory.build(publicKeyURL, FederationIDPIssuerType.CSP);
    }

    private void writeKey(String issuer, FederatedTokenPublicKey key) throws Exception {
        try {
            writeLockKeyLookup.lock();
            publicKeyLookup.put(issuer, key);
        } finally {
            writeLockKeyLookup.unlock();
        }
    }
}