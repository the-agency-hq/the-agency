[#ftl/]
[#setting url_escaping_charset="UTF-8"]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="bootstrapWebauthnEnabled" type="boolean" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="code_challenge" type="java.lang.String" --]
[#-- @ftlvariable name="code_challenge_method" type="java.lang.String" --]
[#-- @ftlvariable name="devicePendingIdPLink" type="io.fusionauth.domain.provider.PendingIdPLink" --]
[#-- @ftlvariable name="federatedCSRFToken" type="java.lang.String" --]
[#-- @ftlvariable name="hasDomainBasedIdentityProviders" type="boolean" --]
[#-- @ftlvariable name="identityProviders" type="java.util.Map<java.lang.String, java.util.List<io.fusionauth.domain.provider.BaseIdentityProvider<?>>>" --]
[#-- @ftlvariable name="idpRedirectState" type="java.lang.String" --]
[#-- @ftlvariable name="loginId" type="java.lang.String" --]
[#-- @ftlvariable name="metaData" type="io.fusionauth.domain.jwt.RefreshToken.MetaData" --]
[#-- @ftlvariable name="nonce" type="java.lang.String" --]
[#-- @ftlvariable name="passwordlessEnabled" type="boolean" --]
[#-- @ftlvariable name="pendingIdPLink" type="io.fusionauth.domain.provider.PendingIdPLink" --]
[#-- @ftlvariable name="redirect_uri" type="java.lang.String" --]
[#-- @ftlvariable name="rememberDevice" type="boolean" --]
[#-- @ftlvariable name="response_type" type="java.lang.String" --]
[#-- @ftlvariable name="scope" type="java.lang.String" --]
[#-- @ftlvariable name="showCaptcha" type="boolean" --]
[#-- @ftlvariable name="showPasswordField" type="boolean" --]
[#-- @ftlvariable name="showWebAuthnReauthLink" type="boolean" --]
[#-- @ftlvariable name="state" type="java.lang.String" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#-- @ftlvariable name="timezone" type="java.lang.String" --]
[#-- @ftlvariable name="user_code" type="java.lang.String" --]
[#-- @ftlvariable name="version" type="java.lang.String" --]
[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("oauth2-authorize-page-title")]
    <script src="${request.contextPath}/js/jstz-min-1.0.6.js"></script>
    [@helpers.captchaScripts showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]
    <script src="${request.contextPath}/js/oauth2/Authorize.js?version=${version}"></script>
    <script src="${request.contextPath}/js/identityProvider/InProgress.js?version=${version}"></script>
    [@helpers.alternativeLoginsScript clientId=client_id identityProviders=identityProviders/]
    <script>
      Prime.Document.onReady(function() {
        [#-- This object handles guessing the timezone, filling in the device id of the user, and check for WebAuthn re-authentication support --]
        new FusionAuth.OAuth2.Authorize();
      });
    </script>
  [/@helpers.head]
  [@helpers.body]

    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.main title=theme.message("login")]
      [#-- During a linking work flow, optionally indicate to the user which IdP is being linked. --]
      [#if devicePendingIdPLink?? || pendingIdPLink??]
        <p class="mt-0">
        [#if devicePendingIdPLink?? && pendingIdPLink??]
          ${theme.message('pending-links-login-to-complete', devicePendingIdPLink.identityProviderName, pendingIdPLink.identityProviderName)}
        [#elseif devicePendingIdPLink??]
          ${theme.message('pending-link-login-to-complete', devicePendingIdPLink.identityProviderName)}
        [#else]
          ${theme.message('pending-link-login-to-complete', pendingIdPLink.identityProviderName)}
        [/#if]
        [#-- A pending link can be cancled. If we also have a device link in progress, this cannot be canceled. --]
        [#if pendingIdPLink??]
          [@helpers.link url="" extraParameters="&cancelPendingIdpLink=true"]${theme.message("login-cancel-link")}[/@helpers.link]
        [/#if]
        </p>
      [/#if]

      [#-- The structuredForm component recognizes two form areas and formats them a little differently. Note that there are no fieldset or form-row
           elements, nor any margin, padding, or other spacing-related classes. There's one div left that's used to put the remember me and forgot
           password items on the same line. --]
      [@helpers.structuredForm action="${request.contextPath}/oauth2/authorize" method="POST"; section]
        [#-- The formFields section contains input fields, which are spaced closely together --]
        [#if section == "formFields"]
          [@helpers.oauthHiddenFields/]
          [@helpers.hidden name="showPasswordField"/]
          [@helpers.hidden name="userVerifyingPlatformAuthenticatorAvailable"/]
          [#if showPasswordField && hasDomainBasedIdentityProviders]
            [@helpers.hidden name="loginId"/]
          [/#if]

          [@helpers.input type="text" name="loginId" id="loginId" autocomplete="username" autocapitalize="none" autocomplete="on" autocorrect="off" spellcheck="false" autofocus=(!loginId?has_content) placeholder=theme.message("loginId") leftAddon="user" disabled=(showPasswordField && hasDomainBasedIdentityProviders)/]
          [#if showPasswordField]
            [@helpers.input type="password" name="password" id="password" autocomplete="current-password" autofocus=loginId?has_content placeholder=theme.message("password") leftAddon="lock"/]

            [@helpers.captchaBadge showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]
          [/#if]

        [#-- The buttons section contains links, checkboxes, and form buttons, which are spaced a little farther apart --]
        [#elseif section == "buttons"]
          [@helpers.input id="rememberDevice" type="checkbox" name="rememberDevice" label=theme.message('remember-device') value="true" uncheckedValue="false" tooltip=theme.message("{tooltip}remember-device")/]

          [#if showPasswordField]
            <div class="flex flex-col space-y-4 items-center">
              [@helpers.button id="submit-button" icon="key" text=theme.message("submit")/]
              [@helpers.link url="${request.contextPath}/password/forgot"]${theme.message("forgot-your-password")}[/@helpers.link]
            </div>
          [#else]
            [@helpers.button icon="arrow-right" text=theme.message("next")/]
          [/#if]

          [#if showPasswordField && hasDomainBasedIdentityProviders]
            [@helpers.link url="" extraParameters="&showPasswordField=false"]${theme.message("sign-in-as-different-user")}[/@helpers.link]
          [/#if]

          [#if application.registrationConfiguration.enabled]
            <span class="text-sm/6">${theme.message("dont-have-an-account")}</span>
            [@helpers.link url="${request.contextPath}/oauth2/register"]${theme.message("create-an-account")}[/@helpers.link]
          [/#if]

          [#if showWebAuthnReauthLink]
            <div class="flex justify-center">
              [@helpers.link url="${request.contextPath}/oauth2/webauthn-reauth"] ${theme.message("return-to-webauthn-reauth")} [/@helpers.link]
            </div>
          [/#if]

        [/#if]
      [/@helpers.structuredForm]
      [@helpers.alternativeLogins clientId=client_id identityProviders=identityProviders passwordlessEnabled=passwordlessEnabled bootstrapWebauthnEnabled=bootstrapWebauthnEnabled idpRedirectState=idpRedirectState federatedCSRFToken=federatedCSRFToken/]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]

  [/@helpers.body]
[/@helpers.html]
