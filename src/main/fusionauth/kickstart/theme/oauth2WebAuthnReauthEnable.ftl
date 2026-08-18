[#ftl/]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="currentUser" type="io.fusionauth.domain.User" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="webAuthnCredentials" type="java.util.List<io.fusionauth.domain.WebAuthnCredential>" --]
[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("oauth2-webauthn-reauth-enable-page-title")]
    <script src="${request.contextPath}/js/FormHelper.js?version=${version}"></script>
    <script src="${request.contextPath}/js/WebAuthnHelper.js?version=${version}"></script>
    <script src="${request.contextPath}/js/oauth2/OAuth2WebAuthnLogin.js?version=${version}"></script>
    <script src="${request.contextPath}/js/oauth2/OAuth2WebAuthnRegistration.js?version=${version}"></script>
    <script>
      Prime.Document.onReady(function() {
        new OAuth2WebAuthnRegistration(document.getElementById("webauthn-register-form"));
        new OAuth2WebAuthnLogin(
          document.querySelector('form[id="webauthn-login-form"]'),
          document.querySelector('input[type="hidden"][name="webAuthnLoginRequest"]')
        );
      });
    </script>
  [/@helpers.head]

  [@helpers.body]
    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.main title=theme.message("no-password")]
      [#setting url_escaping_charset='UTF-8']

        [@helpers.structuredForm id="webauthn-login-form" action="${request.contextPath}/oauth2/webauthn-reauth-enable" method="POST"; section]
          [#if section == "formFields"]
            [@helpers.oauthHiddenFields/]
            [@helpers.hidden name="webAuthnLoginRequest"/]
            [@helpers.hidden name="workflow" value="reauthentication"/]

            <p class="text-md">${theme.message("{description}webauthn-reauth")}</p>
          [#elseif section == "buttons"]

            [#-- List existing credentials for re-authentication if any exist. --]
            [#if webAuthnCredentials?has_content]
              <p>${theme.message("{description}webauthn-reauth-existing-credential")}</p>
              <fieldset class="mt-3 hover push-bottom">
                [#list webAuthnCredentials![] as credential]
                 <button class="chunky-wide-submit" name="credentialId" value="${credential.id}">
                    <span>
                      <span>${helpers.display(credential, "displayName")}</span>
                      <span class="sub-text">
                        ${theme.message("last-used")}
                        ${theme.formatZoneDateTime(credential.lastUseInstant, theme.message("date-format"), zoneId)}</span>
                    </span>
                    <i class="fa fa-chevron-right"></i>
                  </button>
                [/#list]
              </fieldset>
            [/#if]

            [@helpers.input id="doNotAskAgain" type="checkbox" name="doNotAskAgain" label=theme.message('dont-ask-again') value="true" uncheckedValue="false"/]
            [@helpers.button icon="check" name="action" value="skip" text=theme.message("not-now") /]
          [/#if]
        [/@helpers.structuredForm]

        [#-- Description for adding a new credential during login. --]
        <div class="mt-8 ">
          [#if webAuthnCredentials?has_content]
            ${theme.message("or")} ${theme.message("{description}webauthn-reauth-add-credential")?uncap_first}
          [#else]
            ${theme.message("{description}webauthn-reauth-add-credential")}
          [/#if]
        </div>

        [@helpers.structuredForm id="webauthn-register-form" action="${request.contextPath}/oauth2/webauthn-reauth-enable" method="POST" dataAttributes={"start-registration-action": "/oauth2/ajax/webauthn/start-registration"}; section]
          [#if section == "buttons"]
            [@helpers.oauthHiddenFields/]
            [@helpers.hidden name="webAuthnRegisterRequest"/]
            [@helpers.hidden name="workflow" value="reauthentication"/]

            [#-- Default the displayName on initial render if not alread set. --]
            [#if !(displayName??) && request.method == "GET"]
              [#global displayName = theme.message('unnamed')/]
            [/#if]

            [@helpers.input type="text" name="displayName" id="displayName" label="${theme.message('display-name')}" autocomplete="off" autofocus=true required=true/]
            [@helpers.button icon="key" name="action" value="register" text=theme.message("register") /]
          [/#if]

        [/@helpers.structuredForm]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
