[#ftl/]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="webAuthnCredentials" type="java.util.List<io.fusionauth.domain.WebAuthnCredential>" --]
[#import "../_helpers.ftl" as helpers/]

[#-- Contents of the passkey button --]
[#macro passKey user]
  [#if user.name??]
    ${user.name}
  [#elseif user.uniqueUsername??]
    ${user.uniqueUsername}
  [#else]
    ${helpers.display(user, "loginId")}
  [/#if]
[/#macro]

[@helpers.html]
  [@helpers.head title=theme.message("oauth2-webauthn-reauth-page-title")]
    <script src="${request.contextPath}/js/FormHelper.js?version=${version}"></script>
    <script src="${request.contextPath}/js/oauth2/OAuth2WebAuthnLogin.js?version=${version}"></script>
    <script>
      document.addEventListener('DOMContentLoaded', () => {
        new OAuth2WebAuthnLogin(
          document.querySelector('form[id="webauthn-login-form"]'),
          document.querySelector('input[type="hidden"][name="webAuthnRequest"]')
        );
      });
    </script>
  [/@helpers.head]

  [@helpers.body]
    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]
    [@helpers.main title=theme.message("login-with-passkey")]
      [#setting url_escaping_charset='UTF-8']
        [@helpers.structuredForm id="webauthn-login-form" action="${request.contextPath}/oauth2/webauthn-reauth" method="POST"; section]

          [#if section == "formFields"]
            [@helpers.oauthHiddenFields/]
            [@helpers.hidden name="webAuthnRequest" /]
            [@helpers.hidden name="workflow" value="reauthentication"/]
            [@helpers.hidden name="userVerifyingPlatformAuthenticatorAvailable" /]

            ${theme.message("webauthn-reauth-select-passkey")}

            [#list webAuthnCredentials![] as credential]
              <button class="chunky-wide-submit py-3 flex items-center w-full hover:bg-input-text/30 focus:bg-input-text/30" name="credentialId" value="${credential.id}">
                <span class="flex flex-col items-start flex-grow">
                  <span class="font-medium">[@passKey users(credential.userId)/]</span>
                  <span>${helpers.display(credential, "displayName")}</span>
                </span>
                <i class="fa fa-chevron-right"></i>
              </button>
            [#sep]<div class="border-1 border-input-text/50"></div>
            [/#list]

          [#elseif section == "buttons"]

            <p>${theme.message("webauthn-reauth-return-to-login")}</p>

            [@helpers.input id="rememberDevice" type="checkbox" name="rememberDevice" label=theme.message('remember-device') value="true" uncheckedValue="false" tooltip=theme.message("{tooltip}remember-device")/]

            <p class="mt-2">[@helpers.link url="/oauth2/authorize" extraParameters="&skipWebAuthnReauth=true"]${theme.message("return-to-normal-login")}[/@helpers.link]</p>
          [/#if]
        [/@helpers.structuredForm]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
