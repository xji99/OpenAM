/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2016 ForgeRock AS.
 * Portions Copyrighted 2015 Nomura Research Institute, Ltd.
 */

package org.forgerock.openam.oauth2;

import static org.forgerock.json.JsonValue.*;
import static org.forgerock.oauth2.core.Utils.isEmpty;
import static org.forgerock.oauth2.core.Utils.joinScope;
import static org.forgerock.openam.oauth2.OAuth2Constants.OAuth2ProviderService.*;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.AccessController;
import java.security.Key;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.authentication.AuthContext;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.idm.IdUtils;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.shared.encode.Hash;
import com.sun.identity.sm.DNMapper;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceConfigManager;
import com.sun.identity.sm.ServiceListener;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.forgerock.guice.core.InjectorHolder;
import org.forgerock.json.JsonValue;
import org.forgerock.json.jose.jwk.KeyUse;
import org.forgerock.json.jose.jws.JwsAlgorithm;
import org.forgerock.json.jose.jws.SupportedEllipticCurve;
import org.forgerock.oauth2.core.AccessToken;
import org.forgerock.oauth2.core.AuthenticationMethod;
import org.forgerock.oauth2.core.ClientRegistration;
import org.forgerock.oauth2.core.NoneResponseTypeHandler;
import org.forgerock.oauth2.core.OAuth2ProviderSettings;
import org.forgerock.oauth2.core.OAuth2Request;
import org.forgerock.oauth2.core.ResourceOwner;
import org.forgerock.oauth2.core.ResponseTypeHandler;
import org.forgerock.oauth2.core.ScopeValidator;
import org.forgerock.oauth2.core.Token;
import org.forgerock.oauth2.core.UserInfoClaims;
import org.forgerock.oauth2.core.exceptions.InvalidClientException;
import org.forgerock.oauth2.core.exceptions.InvalidRequestException;
import org.forgerock.oauth2.core.exceptions.InvalidScopeException;
import org.forgerock.oauth2.core.exceptions.NotFoundException;
import org.forgerock.oauth2.core.exceptions.ServerException;
import org.forgerock.oauth2.core.exceptions.UnauthorizedClientException;
import org.forgerock.oauth2.core.exceptions.UnsupportedResponseTypeException;
import org.forgerock.oauth2.resources.ResourceSetStore;
import org.forgerock.openam.oauth2.OAuth2Constants.OAuth2ProviderService;
import org.forgerock.openam.utils.OpenAMSettingsImpl;
import org.forgerock.openam.utils.StringUtils;
import org.forgerock.openidconnect.Client;
import org.forgerock.util.annotations.VisibleForTesting;
import org.forgerock.util.encode.Base64url;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Models all of the possible settings the OpenAM OAuth2 provider can have and that can be configured.
 *
 * @since 12.0.0
 */
public class OpenAMOAuth2ProviderSettings extends OpenAMSettingsImpl implements OAuth2ProviderSettings {

    private final Debug logger = Debug.getInstance("OAuth2Provider");
    private final String realm;
    private final ResourceSetStore resourceSetStore;
    private final CookieExtractor cookieExtractor;
    private ScopeValidator scopeValidator;
    private volatile Template loginUrlTemplate;

    /**
     * Constructs a new OpenAMOAuth2ProviderSettings.
     *
     * @param realm The realm.
     * @param resourceSetStore An instance of the ResourceSetStore for the current realm.
     * @param cookieExtractor An instance of the CookieExtractor.
     */
    public OpenAMOAuth2ProviderSettings(String realm, ResourceSetStore resourceSetStore,
            CookieExtractor cookieExtractor) {
        super(OAuth2ProviderService.NAME, OAuth2ProviderService.VERSION);
        this.realm = realm;
        this.resourceSetStore = resourceSetStore;
        this.cookieExtractor = cookieExtractor;
        addServiceListener();
    }

    private void addServiceListener() {
        try {
            final SSOToken token = AccessController.doPrivileged(AdminTokenAction.getInstance());
            final ServiceConfigManager serviceConfigManager = new ServiceConfigManager(token,
                    OAuth2ProviderService.NAME, OAuth2ProviderService.VERSION);
            if (serviceConfigManager.addListener(new OAuth2ProviderSettingsChangeListener()) == null) {
                logger.error("Could not add listener to ServiceConfigManager instance. OAuth2 provider service " +
                        "changes will not be dynamically updated for realm " + realm);
            }
        } catch (Exception e) {
            String message = "OAuth2Utils::Unable to construct ServiceConfigManager: " + e;
            logger.error(message, e);
            throw OAuthProblemException.OAuthError.SERVER_ERROR.handle(null, message);
        }
    }

    private final Map<String, Set<String>> attributeCache = new HashMap<String, Set<String>>();
    private final List<Map<String, Object>> jwks = new ArrayList<Map<String, Object>>();
    private Set<String> supportedScopesWithoutTranslations;
    private Set<String> supportedClaimsWithoutTranslations;

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> getSetting(String realm, String attributeName) throws SSOException, SMSException {
        synchronized (attributeCache) {
            Set<String> value = attributeCache.get(attributeName);
            if (value == null) {
                value = super.getSetting(realm, attributeName);
                attributeCache.put(attributeName, value);
            }
            return value;
        }
    }

    private Set<String> getSettingStrings(String key) throws ServerException {
        try {
            return getSetting(realm, key);
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public boolean isStatelessTokensEnabled() throws ServerException {
        try {
            return getBooleanSetting(realm, STATELESS_TOKENS_ENABLED);
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public String getTokenSigningAlgorithm() throws ServerException {
        try {
            return getStringSetting(realm, "tokenSigningAlgorithm");
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public String getTokenHmacSharedSecret() throws ServerException {
        try {
            return getStringSetting(realm, "tokenSigningHmacSharedSecret");
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Map<String, ResponseTypeHandler> getAllowedResponseTypes() throws UnsupportedResponseTypeException,
            ServerException {
        try {
            Set<String> responseTypeSet = getSetting(realm, OAuth2ProviderService.RESPONSE_TYPE_LIST);
            if (responseTypeSet == null || responseTypeSet.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, ResponseTypeHandler> responseTypes = new HashMap<String, ResponseTypeHandler>();
            for (String responseType : responseTypeSet){
                String[] parts = responseType.split("\\|");
                if (parts.length != 2){
                    logger.error("Response type wrong format for realm: " + realm);
                    continue;
                }
                responseTypes.put(parts[0], wrap(parts[0], parts[1]));
            }
            return responseTypes;

        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    private ResponseTypeHandler wrap(String responseTypeName, String responseTypeHandlerClassName)
            throws UnsupportedResponseTypeException {

        if (responseTypeHandlerClassName == null || responseTypeHandlerClassName.isEmpty()) {
            logger.warning("Requested a response type that is not configured. response_type=" + responseTypeName);
            throw new UnsupportedResponseTypeException("Response type is not supported");
        } else if (responseTypeHandlerClassName.equalsIgnoreCase("none")) {
            return new NoneResponseTypeHandler();
        }
        try {
            final Class<?> responseTypeHandlerClass = Class.forName(responseTypeHandlerClassName);
            return InjectorHolder.getInstance(responseTypeHandlerClass.asSubclass(ResponseTypeHandler.class));
        } catch (ClassNotFoundException e) {
            logger.error(e.getMessage());
            throw new UnsupportedResponseTypeException("Response type is not supported");
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isSaveConsentEnabled() {
        try {
            return getStringSetting(realm, OAuth2ProviderService.SAVED_CONSENT_ATTRIBUTE)!= null;
        } catch (SMSException | SSOException e) {
            logger.error("There was a problem getting the consent configuration for realm:" + realm, e);
        }
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean isConsentSaved(ResourceOwner resourceOwner, String clientId, Set<String> scope) {
        String consentAttribute = "";
        try {
            if (isSaveConsentEnabled()) {
                consentAttribute = getStringSetting(realm, OAuth2ProviderService.SAVED_CONSENT_ATTRIBUTE);
                AMIdentity id = ((OpenAMResourceOwner) resourceOwner).getIdentity();
                if (id != null) {
                    Set<String> attributeSet = id.getAttribute(consentAttribute);
                    if (attributeSet != null) {
                        if (logger.messageEnabled()) {
                            logger.message("Existing saved consent value for resourceOwner: " + resourceOwner.getId() +
                                   " in attribute:" + consentAttribute + " in realm:" + realm + " is:" + attributeSet);
                        }
                        //check the values of the attribute set vs the scope and client requested
                        //attribute set is in the form of client_id|scope1 scope2 scope3
                        for (String consent : attributeSet) {
                            int loc = consent.indexOf(" ");
                            String consentClientId = consent.substring(0, loc);
                            String[] scopesArray = null;
                            if (loc + 1 < consent.length()) {
                                scopesArray = consent.substring(loc + 1, consent.length()).split(" ");
                            }
                            Set<String> consentScopes;
                            if (scopesArray != null && scopesArray.length > 0) {
                                consentScopes = new HashSet<String>(Arrays.asList(scopesArray));
                            } else {
                                consentScopes = new HashSet<String>();
                            }

                            //if both the client and the scopes are identical to the saved consent then approve
                            if (clientId.equals(consentClientId) && scope.equals(consentScopes)) {
                                return true;
                            }
                        }
                    } else {
                        if (logger.messageEnabled()) {
                            logger.message("No existing saved consent value for resourceOwner: " + resourceOwner.getId()
                                    + " in attribute:" + consentAttribute + " in realm:" + realm);
                        }
                    }
                }
            } else {
                logger.error("Can't save consent as it is not configured properly for the realm:" + realm);
            }
        } catch (Exception e) {
            logger.error("There was a problem getting the saved consent from the attribute: "
                    + consentAttribute + " for realm:" + realm, e);
        }

        return false;
    }

    private synchronized ScopeValidator getScopeValidator() throws ServerException {
        if (scopeValidator == null) {
            try {
                final String scopeValidatorClassName = getStringSettingValue(OAuth2ProviderService.SCOPE_PLUGIN_CLASS);
                if (isEmpty(scopeValidatorClassName)) {
                    logger.message("Scope Validator class not set.");
                    throw new ServerException("Scope Validator class not set.");
                }
                final Class<?> scopeValidatorClass = Class.forName(scopeValidatorClassName);
                scopeValidator = InjectorHolder.getInstance(scopeValidatorClass.asSubclass(ScopeValidator.class));
            } catch (ClassNotFoundException e) {
                logger.error(e.getMessage());
                throw new ServerException(e);
            }
        }
        return scopeValidator;
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> validateAuthorizationScope(ClientRegistration clientRegistration, Set<String> scope,
            OAuth2Request request) throws ServerException, InvalidScopeException {
        return getScopeValidator().validateAuthorizationScope(clientRegistration, scope, request);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> validateAccessTokenScope(ClientRegistration clientRegistration, Set<String> scope,
            OAuth2Request request) throws ServerException, InvalidScopeException {
        return getScopeValidator().validateAccessTokenScope(clientRegistration, scope, request);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> validateRefreshTokenScope(ClientRegistration clientRegistration, Set<String> requestedScope,
            Set<String> tokenScope, OAuth2Request request) throws ServerException, InvalidScopeException {
        return getScopeValidator().validateRefreshTokenScope(clientRegistration, requestedScope, tokenScope, request);
    }

    /**
     * {@inheritDoc}
     */
    public UserInfoClaims getUserInfo(AccessToken token, OAuth2Request request) throws ServerException,
            UnauthorizedClientException, NotFoundException {
        return getScopeValidator().getUserInfo(token, request);
    }

    /**
     * {@inheritDoc}
     */
    public Map<String, Object> evaluateScope(AccessToken accessToken) throws ServerException {
        return getScopeValidator().evaluateScope(accessToken);
    }

    /**
     * {@inheritDoc}
     */
    public Map<String, String> additionalDataToReturnFromAuthorizeEndpoint(Map<String, Token> tokens,
            OAuth2Request request) throws ServerException {
        return getScopeValidator().additionalDataToReturnFromAuthorizeEndpoint(tokens, request);
    }

    /**
     * {@inheritDoc}
     */
    public void additionalDataToReturnFromTokenEndpoint(AccessToken accessToken, OAuth2Request request)
            throws ServerException, InvalidClientException, NotFoundException {
        getScopeValidator().additionalDataToReturnFromTokenEndpoint(accessToken, request);
    }

    /**
     * {@inheritDoc}
     */
    public void saveConsent(ResourceOwner resourceOwner, String clientId, Set<String> scope) {

        String consentAttribute = null;
        try {
            consentAttribute = getStringSetting(realm, OAuth2ProviderService.SAVED_CONSENT_ATTRIBUTE);
            if (consentAttribute != null) {
                AMIdentity id = ((OpenAMResourceOwner) resourceOwner).getIdentity();
                //get the current set of consents and add our new consent to it if they exist.
                Set<String> existing = id.getAttribute(consentAttribute);
                Set<String> consents = (existing != null) ? new HashSet<String>(existing) : new HashSet<String>(1);
                StringBuilder sb = new StringBuilder();
                if (scope == null || scope.isEmpty()) {
                    sb.append(clientId.trim()).append(" ");
                } else {
                    sb.append(clientId.trim()).append(" ").append(joinScope(scope));
                }
                consents.add(sb.toString());

                if (logger.messageEnabled()) {
                    logger.message("Saving consents:" + consents + " for resourceOwner: " + resourceOwner.getId()
                            + " in attribute:" + consentAttribute + " in realm:" + realm);
                }
                updateConsentValues(consentAttribute, id, consents);

            } else {
                logger.error("Cannot save consent as no saved consent attribute defined in realm:" + realm);
            }
        } catch (Exception e) {
            logger.error("There was a problem saving the consent into the attribute: {} for realm: {}",
                    consentAttribute, realm, e);
        }
    }

    @Override
    public void revokeConsent(String userId, String clientId) {
        String consentAttribute = null;
        try {
            consentAttribute = getStringSetting(realm, OAuth2ProviderService.SAVED_CONSENT_ATTRIBUTE);
            if (consentAttribute != null) {
                AMIdentity id = IdUtils.getIdentity(userId, realm);

                Set<String> consents = id.getAttribute(consentAttribute);
                if (consents == null) {
                    return;
                }

                Iterator<String> iterator = consents.iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().startsWith(clientId + " ")) {
                        iterator.remove();
                    }
                }
                updateConsentValues(consentAttribute, id, consents);
            }
        } catch (SMSException | SSOException | IdRepoException e) {
            logger.warning("There was a problem revoking consent from the attribute: {} for realm: {}",
                    consentAttribute, realm, e);
        }

    }

    private void updateConsentValues(String consentAttribute, AMIdentity id, Set<String> consents) throws IdRepoException, SSOException {
        //update the user profile with our new consent settings
        Map<String, Set<String>> attrs = new HashMap<>(1);
        attrs.put(consentAttribute, consents);
        id.setAttributes(attrs);
        id.store();
    }

    /**
     * {@inheritDoc}
     */
    public boolean issueRefreshTokens() throws ServerException {
        try {
            return getBooleanSetting(realm, OAuth2ProviderService.ISSUE_REFRESH_TOKEN);
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean issueRefreshTokensOnRefreshingToken() throws ServerException {
        try {
            return getBooleanSetting(realm, OAuth2ProviderService.ISSUE_REFRESH_TOKEN_ON_REFRESHING_TOKEN);
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public long getAuthorizationCodeLifetime() throws ServerException {
        return getLongSettingValue(OAuth2ProviderService.AUTHZ_CODE_LIFETIME_NAME);
    }

    /**
     * {@inheritDoc}
     */
    public long getAccessTokenLifetime() throws ServerException {
        return getLongSettingValue(OAuth2ProviderService.ACCESS_TOKEN_LIFETIME_NAME);
    }

    /**
     * {@inheritDoc}
     */
    public long getOpenIdTokenLifetime() throws ServerException {
        return getLongSettingValue(OAuth2ProviderService.JWT_TOKEN_LIFETIME_NAME);
    }

    /**
     * {@inheritDoc}
     */
    public long getRefreshTokenLifetime() throws ServerException {
        return getLongSettingValue(OAuth2ProviderService.REFRESH_TOKEN_LIFETIME_NAME);
    }

    /**
     * {@inheritDoc}
     */
    public KeyPair getSigningKeyPair(JwsAlgorithm algorithm) throws ServerException {
        try {
            return getSigningKeyPair(realm, algorithm);
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getResourceOwnerAuthenticatedAttributes() throws ServerException {
        return getSettingStrings(OAuth2ProviderService.AUTHENITCATION_ATTRIBUTES);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getSupportedClaims() throws ServerException {
        return supportedClaimsWithoutTranslations = getWithoutTranslations(OAuth2ProviderService.SUPPORTED_CLAIMS,
                supportedClaimsWithoutTranslations);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getSupportedClaimsWithTranslations() throws ServerException {
        return getSettingStrings(OAuth2ProviderService.SUPPORTED_CLAIMS);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getSupportedScopes() throws ServerException {
        return supportedScopesWithoutTranslations = getWithoutTranslations(OAuth2ProviderService.SUPPORTED_SCOPES,
                supportedScopesWithoutTranslations);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getSupportedScopesWithTranslations() throws ServerException {
        return getSettingStrings(OAuth2ProviderService.SUPPORTED_SCOPES);
    }

    private Set<String> getWithoutTranslations(String key, Set<String> cached) throws ServerException {
        if (cached != null) {
            return cached;
        }
        Set<String> claims = new HashSet<>();
        try {
            synchronized (attributeCache) {
                for (String claim : getSetting(realm, key)) {
                    int pipe = claim.indexOf('|');
                    if (pipe > -1) {
                        claims.add(claim.substring(0, pipe));
                    } else {
                        claims.add(claim);
                    }
                }
                return claims;
            }
        } catch (SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getDefaultScopes() throws ServerException {
        return getSettingStrings(OAuth2ProviderService.DEFAULT_SCOPES);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getSupportedIDTokenSigningAlgorithms() throws ServerException {
        return getSettingStrings(OAuth2ProviderService.ID_TOKEN_SIGNING_ALGORITHMS);
    }

    @Override
    public Set<String> getSupportedIDTokenEncryptionAlgorithms() throws ServerException {
        return getSettingStrings("supportedIDTokenEncryptionAlgorithms");
    }

    @Override
    public Set<String> getSupportedIDTokenEncryptionMethods() throws ServerException {
        return getSettingStrings("supportedIDTokenEncryptionMethods");
    }

    /**
     * {@inheritDoc}
     */
    public String getOpenIDConnectVersion() {
        return "3.0";
    }

    @Override
    public boolean getClaimsParameterSupported() throws ServerException {
        try {
            return getBooleanSetting(realm, OAuth2ProviderService.CLAIMS_PARAMETER_SUPPORTED);
        } catch (SSOException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        } catch (SMSException e) {
            logger.error(e.getMessage());
        }

        return false;
    }

    @Override
    public String validateRequestedClaims(String requestedClaims) throws InvalidRequestException, ServerException {

        if (!getClaimsParameterSupported()) {
            return null;
        }

        if (StringUtils.isBlank(requestedClaims)) {
            return null;
        }

        final Set<String> claims = new HashSet<String>();

        try {
            JSONObject json = new JSONObject(requestedClaims);
            JSONObject userinfo = json.optJSONObject(OAuth2Constants.UserinfoEndpoint.USERINFO);
            JSONObject id_token = json.optJSONObject(OAuth2Constants.JWTTokenParams.ID_TOKEN);

            if (userinfo != null) {
                Iterator<String> it = userinfo.keys();
                while (it.hasNext()) {
                    claims.add(it.next());
                }
            }

            if (id_token != null) {
                Iterator<String> it = id_token.keys();
                while (it.hasNext()) {
                    claims.add(it.next());
                }
            }
        } catch (JSONException e) {
            throw new InvalidRequestException("Requested claims must be valid json.");

        }

        if (!getSupportedClaims().containsAll(claims)) {
            throw new InvalidRequestException("Requested claims must be allowed by the client's configuration");
        }

        return requestedClaims;
    }

    @Override
    public Set<String> getEndpointAuthMethodsSupported() {
        Set<String> supported = new HashSet<String>();

        for (Client.TokenEndpointAuthMethod method : Client.TokenEndpointAuthMethod.values()) {
            supported.add(method.getType());
        }

        return supported;
    }

    @Override
    public String getHashSalt() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.HASH_SALT);
    }

    @Override
    public boolean isCodeVerifierRequired() throws ServerException {
        try {
            return getBooleanSetting(realm, OAuth2ProviderService.CODE_VERIFIER);
        } catch (SSOException | SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public boolean isAlwaysAddClaimsToToken() throws ServerException {
        try {
            return getBooleanSetting(realm, OAuth2ProviderService.ALWAYS_ADD_CLAIMS_TO_TOKEN);
        } catch (SSOException | SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public String getUserDisplayNameAttribute() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.USER_DISPLAY_NAME_ATTRIBUTE);
    }

    /**
     * {@inheritDoc}
     */
    public ResourceSetStore getResourceSetStore() {
        return resourceSetStore;
    }

    public JsonValue getJWKSet() throws ServerException {
        synchronized (jwks) {
            if (jwks.isEmpty()) {
                try {
                    Key key = getSigningKeyPair(realm, JwsAlgorithm.RS256).getPublic();
                    if (key != null && "RSA".equals(key.getAlgorithm())) {
                        jwks.add(createRSAJWK(getTokenSigningRSAKeyAlias(), (RSAPublicKey) key, KeyUse.SIG,
                                JwsAlgorithm.RS256.name()));
                    } else {
                        logger.error("Incorrect Public Key type for RSA signing algorithm");
                    }

                    Set<String> ecdsaAlgorithmAliases = getSetting(realm, TOKEN_SIGNING_ECDSA_KEYSTORE_ALIAS);
                    for (String algorithmAlias : ecdsaAlgorithmAliases) {
                        if (StringUtils.isEmpty(algorithmAlias)) {
                            logger.warning("Empty ECDSA signing key alias");
                            continue;
                        }
                        String[] aliasSplit = algorithmAlias.split("\\|");
                        if (aliasSplit.length != 2) {
                            logger.warning("Invalid ECDSA signing key alias mapping: " + algorithmAlias);
                            continue;
                        }
                        String alias = aliasSplit[1];
                        key = getSigningKeyPair(realm, JwsAlgorithm.valueOf(aliasSplit[0].toUpperCase())).getPublic();
                        if (key == null) {
                            continue;
                        }
                        if ("EC".equals(key.getAlgorithm())) {
                            jwks.add(createECJWK(alias, (ECPublicKey) key, KeyUse.SIG));
                        } else {
                            logger.error("Incorrect Public Key type for ECDSA signing algorithm. Alias: "
                                    + algorithmAlias);
                        }
                    }
                } catch (SMSException | SSOException e) {
                    throw new ServerException(e);
                }
            }
        }
        return new JsonValue(Collections.singletonMap("keys", jwks));
    }

    @VisibleForTesting
    static Map<String, Object> createRSAJWK(String alias, RSAPublicKey key, KeyUse use, String alg)
            throws ServerException {
        String kid = Hash.hash(alias + key.getModulus().toString() + key.getPublicExponent().toString());
        return json(object(field("kty", "RSA"), field(OAuth2Constants.JWTTokenParams.KEY_ID, kid),
                field("use", use.toString()), field("alg", alg),
                field("n", Base64url.encode(key.getModulus().toByteArray())),
                field("e", Base64url.encode(key.getPublicExponent().toByteArray())))).asMap();
    }

    @VisibleForTesting
    static Map<String, Object> createECJWK(String alias, ECPublicKey key, KeyUse use) throws ServerException {
        BigInteger x = key.getW().getAffineX();
        BigInteger y = key.getW().getAffineY();
        SupportedEllipticCurve curve = SupportedEllipticCurve.forKey(key);
        String kid = Hash.hash(alias + ':' + curve.getStandardName() + ':' + x.toString() + ':' + y.toString());
        return json(object(field("kty", "EC"), field(OAuth2Constants.JWTTokenParams.KEY_ID, kid),
                field("use", use.toString()), field("alg", curve.getJwsAlgorithm().name()),
                field("x", Base64url.encode(x.toByteArray())),
                field("y", Base64url.encode(y.toByteArray())),
                field("crv", curve.getStandardName()))).asMap();
    }

    private String getTokenSigningRSAKeyAlias() throws ServerException {
        String alias;
        try {
            alias = getStringSetting(realm, TOKEN_SIGNING_RSA_KEYSTORE_ALIAS);
        } catch (SSOException | SMSException e) {
            logger.error(e.getMessage());
            throw new ServerException(e);
        }
        if (StringUtils.isBlank(alias)) {
            logger.error("Alias of Token Signing Key not set.");
            throw new ServerException("Alias of Token Signing Key not set.");
        } else if ("test".equals(alias)) {
            logger.warning("Alias of Token Signing Key should be changed from default, 'test'.");
        }
        return alias;
    }

    public String getCreatedTimestampAttributeName() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.CREATED_TIMESTAMP_ATTRIBUTE_NAME);
    }


    public String getModifiedTimestampAttributeName() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.MODIFIED_TIMESTAMP_ATTRIBUTE_NAME);
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getSupportedSubjectTypes() throws ServerException {
        return getSettingStrings(OAuth2ProviderService.SUBJECT_TYPES_SUPPORTED);
    }

    @Override
    public boolean isOpenDynamicClientRegistrationAllowed() throws ServerException {
        try {
            return getBooleanSetting(realm, OAuth2ProviderService.OPEN_DYNAMIC_REGISTRATION_ALLOWED);
        } catch (SSOException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        } catch (SMSException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public boolean isRegistrationAccessTokenGenerationEnabled() throws ServerException {
        try {
            return getBooleanSetting(realm, OAuth2ProviderService.GENERATE_REGISTRATION_ACCESS_TOKENS);
        } catch (SSOException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        } catch (SMSException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public Map<String, AuthenticationMethod> getAcrMapping() throws ServerException {
        try {
            final Map<String, String> map = getMapSetting(realm,
                    OAuth2ProviderService.ACR_VALUE_MAPPING);
            final Map<String, AuthenticationMethod> methods = new HashMap<String, AuthenticationMethod>(map.size());
            for (Map.Entry<String, String> entry : map.entrySet()) {
                methods.put(entry.getKey(),
                        new OpenAMAuthenticationMethod(entry.getValue(), AuthContext.IndexType.SERVICE));
            }
            return methods;
        } catch (SSOException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        } catch (SMSException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public String getDefaultAcrValues() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.DEFAULT_ACR);
    }

    private String getStringSettingValue(String key) throws ServerException {
        try {
            return getStringSetting(realm, key);
        } catch (SSOException | SMSException e) {
            logger.message("Could not get value of " + key, e);
            throw new ServerException(e);
        }
    }

    private long getLongSettingValue(String key) throws ServerException {
        try {
            return getLongSetting(realm, key);
        } catch (SSOException | SMSException e) {
            logger.error("Could not get value of " + key, e);
            throw new ServerException(e);
        }
    }

    @Override
    public Map<String, String> getAMRAuthModuleMappings() throws ServerException {
        try {
            return getMapSetting(realm, OAuth2ProviderService.AMR_VALUE_MAPPING);
        } catch (SSOException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        } catch (SMSException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public boolean exists() {
        try {
            return hasConfig(realm);
        } catch (Exception e) {
            logger.message("Could not access realm config", e);
            return false;
        }
    }

    @Override
    public Template getCustomLoginUrlTemplate() throws ServerException {
        try {
            String loginUrlTemplateString = getStringSetting(realm, OAuth2ProviderService.RESOURCE_OWNER_CUSTOM_LOGIN_URL_TEMPLATE);
            if (loginUrlTemplateString != null) {
                loginUrlTemplate = new Template("customLoginUrlTemplate", new StringReader(loginUrlTemplateString),
                        new Configuration());
            }
            return loginUrlTemplate;
        } catch (SSOException | IOException | SMSException e) {
            logger.message(e.getMessage());
            throw new ServerException(e);
        }
    }

    @Override
    public String getVerificationUrl() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.DEVICE_VERIFICATION_URL);
    }

    @Override
    public String getJWKSUri() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.JKWS_URI);
    }

    @Override
    public String getCompletionUrl() throws ServerException {
        return getStringSettingValue(OAuth2ProviderService.DEVICE_COMPLETION_URL);
    }

    @Override
    public int getDeviceCodeLifetime() throws ServerException {
        return (int) getLongSettingValue(OAuth2ProviderService.DEVICE_CODE_LIFETIME);
    }

    @Override
    public int getDeviceCodePollInterval() throws ServerException {
        return (int) getLongSettingValue(OAuth2ProviderService.DEVICE_CODE_POLL_INTERVAL);
    }

    @Override
    public boolean shouldStoreOpsTokens() throws ServerException {
        return Boolean.parseBoolean(getStringSettingValue(OAuth2ProviderService.STORE_OPS_TOKENS));
    }

    @Override
    public boolean clientsCanSkipConsent() throws ServerException {
        return Boolean.parseBoolean(getStringSettingValue(OAuth2ProviderService.CLIENTS_CAN_SKIP_CONSENT));
    }

    /**
     * ServiceListener implementation to clear cache when it changes.
     */
    private final class OAuth2ProviderSettingsChangeListener implements ServiceListener {

        public void schemaChanged(String serviceName, String version) {
            logger.warning("The schemaChanged ServiceListener method was invoked for service " + serviceName
                    + ". This is unexpected.");
        }

        public void globalConfigChanged(String serviceName, String version, String groupName, String serviceComponent,
                int type) {
            logger.warning("The globalConfigChanged ServiceListener method was invoked for service " + serviceName);
            //if the global config changes, all organizationalConfig change listeners are invoked as well.
        }

        public void organizationConfigChanged(String serviceName, String version, String orgName, String groupName,
                String serviceComponent, int type) {
            if (currentRealmTargetedByOrganizationUpdate(serviceName, version, orgName, type)) {
                if (logger.messageEnabled()) {
                    logger.message("Updating OAuth service configuration state for realm " + realm);
                }
                synchronized (attributeCache) {
                    attributeCache.clear();
                    jwks.clear();
                    loginUrlTemplate = null;
                }
            } else {
                if (logger.messageEnabled()) {
                    logger.message("Got service update message, but update did not target OAuth2Provider in " +
                            realm + " realm. ServiceName: " + serviceName + " version: " + version + " orgName: " +
                            orgName + " groupName: " + groupName + " serviceComponent: " + serviceComponent +
                            " type (modified=4, delete=2, add=1): " + type + " realm as DN: "
                            + DNMapper.orgNameToDN(realm));
                }
            }
        }

        /*
        The listener receives updates for all changes for each service instance in a given realm. I want to be sure
        that I only pull updates as necessary if the update pertains to this particular realm.
         */
        private boolean currentRealmTargetedByOrganizationUpdate(String serviceName, String version, String orgName,
                int type) {
            return OAuth2ProviderService.NAME.equals(serviceName) &&
                    OAuth2ProviderService.VERSION.equals(version) &&
                    (orgName != null) &&
                    orgName.equalsIgnoreCase(DNMapper.orgNameToDN(realm));
        }
    }
}
