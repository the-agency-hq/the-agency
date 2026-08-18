[#ftl/]
[#setting url_escaping_charset="UTF-8"]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#-- @ftlvariable name="user" type="io.fusionauth.domain.User" --]

[#import "../../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("account-webauthn-add-page-title")]
    <script src="${request.contextPath}/js/account/AccountWebAuthnRegistration.js?version=${version}"></script>
    <script src="${request.contextPath}/js/WebAuthnHelper.js?version=${version}"></script>
    <script>
      document.addEventListener('DOMContentLoaded', () => {
        if (WebAuthnHelper.isWebAuthnSupported()) {
          new AccountWebAuthnRegistration(document.getElementById('webauthn-register-form'));
        } else {
          document.getElementById('no-webauthn-support').classList.remove('hidden');
        }
      });
    </script>
  [/@helpers.head]
  [@helpers.body]

    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.accountMain rowClass="row center" colClass="col-xs-12 col-sm-12 col-md-10 col-lg-8" actionURL="/account/webauthn/" actionText=theme.message("go-back")]
      [@helpers.accountPanelFull title="${theme.message('add-webauthn-passkey')}"]

        <fieldset>
          <p class="mb-3">${theme.message("{description}add-webauthn")}</p>
          <p id="no-webauthn-support" class="hidden mb-3">
            <em>
              <strong>${theme.message("warning")}${theme.message("propertySeparator")}</strong>
              <span>${theme.message("no-webauthn-support")}</span>
            </em>
          </p>
        </fieldset>

        [@helpers.structuredForm id="webauthn-register-form" action="${request.contextPath}/account/webauthn/add" method="POST" dataAttributes={"start-registration-action": "/account/ajax/webauthn/start-registration"}; section]
          [#if section == "formFields"]
            [@helpers.hidden name="client_id" /]
            [@helpers.hidden name="tenantId" /]
            [@helpers.hidden name="webAuthnRegisterRequest" /]

            [#-- Default the displayName on initial render if not alread set. --]
            [#if !(displayName??) && request.method == "GET"]
              [#global displayName = theme.message('unnamed')/]
            [/#if]
            [@helpers.input type="text" id="displayName" name="displayName" autocapitalize="off" autocomplete="off" autofocus=true/]
          [#elseif section == "buttons"]
            [@helpers.button text=theme.message("submit")/]
          [/#if]
        [/@helpers.structuredForm]

      [/@helpers.accountPanelFull]
    [/@helpers.accountMain]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
