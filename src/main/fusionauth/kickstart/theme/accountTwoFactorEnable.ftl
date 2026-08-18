[#ftl/]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="availableMethods" type="java.util.List<java.lang.String>" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="email" type="java.lang.String" --]
[#-- @ftlvariable name="method" type="java.lang.String" --]
[#-- @ftlvariable name="mobilePhone" type="java.lang.String" --]
[#-- @ftlvariable name="phoneMessageTypes" type="java.util.List<java.lang.String>" --]
[#-- @ftlvariable name="recoveryCodes" type="java.util.List<java.lang.String>" --]
[#-- @ftlvariable name="secret" type="java.lang.String" --]
[#-- @ftlvariable name="secretBase32Encoded" type="java.lang.String" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#-- @ftlvariable name="user" type="io.fusionauth.domain.User" --]

[#import "../../_helpers.ftl" as helpers/]

[#macro instructions method]
<div class="d-flex">
  <div style="flex-grow: 1;">
    [#if method == "authenticator"]

      [#-- Authenticator Instructions --]
      <p class="mt-0 mb-3">${theme.message("authenticator-enable-step-1", secretBase32Encoded)?no_esc}</p>
      <p>${theme.message("authenticator-enable-step-2")}</p>

    [#elseif method == "email" || method == "sms"]

      [#-- Email or Phone Instructions --]
      [#assign messageKey = "${method}-enable-step-1"/]
      [#if method == "sms"]
        [#assign smsEnabled = phoneMessageTypes?seq_contains("SMS")/]
        [#assign voiceEnabled = phoneMessageTypes?seq_contains("Voice")/]
        [#if phoneMessageTypes?size == 1 && smsEnabled]
          [#assign messageKey = "sms-enable-smsMessage-step-1"/]
        [#elseif phoneMessageTypes?size == 1 && voiceEnabled]
          [#assign messageKey = "sms-enable-voiceMessage-step-1"/]
        [/#if]
      [/#if]
      <p class="mt-0 mb-3">${theme.message(messageKey)}</p>

      [@helpers.structuredForm id="two-factor-send-form" action="${request.contextPath}/account/two-factor/enable" method="POST"; section]
        [#if section == "formFields"]
          [@helpers.hidden name="action" value="send" /]
          [@helpers.hidden name="client_id" /]
          [@helpers.hidden name="tenantId" /]
          [@helpers.hidden name="method" /]
          [#-- 'secret' and 'twoFactorSecretBase32' are required for authenticator. --]
          [@helpers.hidden name="secret" /]
          [@helpers.hidden name="secretBase32Encoded" /]

          [#-- Send a code --]
          [#if method == "email"]
            [@helpers.input type="text" id="email" name="email" label="Email" required=true/]
          [#elseif method == "sms"]
              [#if phoneMessageTypes?size > 1]
                  [@helpers.select name="messageType" id="select-message-type" options=phoneMessageTypes label="${theme.message('select-two-factor-message-type')}"/]
              [/#if]
              [@helpers.input type="text" id="mobilePhone" name="mobilePhone" label="Mobile phone" required=true/]
          [/#if]
        [#elseif section == "buttons"]
          [@helpers.button icon="arrow-circle-right" text="${theme.message('send-one-time-code')}"/]
        [/#if]
      [/@helpers.structuredForm]
    [/#if]
  </div>

  [#-- QR Code for Authenticator app --]
  [#if method == "authenticator"]
    <div id="qrcode" class="qrcode mt-4 p-4 bg-white rounded-theme"></div>
  [/#if]

</div>
[/#macro]

[@helpers.html]
  [@helpers.head title=theme.message("account-two-factor-enable-page-title")]
    [#-- JavaScript is used for rendering authenticator QR code --]
    <script src="${request.contextPath}/js/qrcode-min-1.0.js"></script>
    <script src="${request.contextPath}/js/account/EnableTwoFactor.js?version=${version}"></script>
    <script>
      Prime.Document.onReady(function() {
        [#-- These variables will get set by the FreeMarker template. --]
        var params = {
          accountName: '${user.getLogin()}',
          issuer: '${tenant.issuer}',
          secretBase32Encoded: '${secretBase32Encoded}'
        }

        [#-- This object the Enable Two-Factor form --]
        new FusionAuth.Account.EnableTwoFactor(params);
      });
    </script>
  [/@helpers.head]
  [@helpers.body]

    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [#assign textKey = recoveryCodes?has_content?then("done", "go-back")/]
    [#assign actionDirection = recoveryCodes?has_content?then("forward", "back")/]
    [#assign panelTitle = recoveryCodes?has_content?then("", theme.message('enable-instructions')) /]

    [@helpers.accountMain rowClass="row center" colClass="col-xs-12 col-sm-12 col-md-10 col-lg-8" actionURL="/account/two-factor/" actionText=theme.message(textKey) actionDirection=actionDirection]
      [@helpers.accountPanelFull title="${panelTitle}"]

        [#-- The first time a user enables a two-factor code, they will be presented with recovery codes. --]
        [#if recoveryCodes?has_content]
          [#-- Display Recovery codes to the user. --]
          <div class="mb-4">
            ${theme.message("{description}recovery-codes-1", recoveryCodes?size)}
          </div>

          <div class="row">
            <div class="col-xs">
              <fieldset>
                [@helpers.codeBox]
                  [#list recoveryCodes as code]
${code}
                  [/#list]
                [/@helpers.codeBox]
              </fieldset>
              <fieldset>
                <p> ${theme.message("{description}recovery-codes-2")}  </p>
              </fieldset>
            </div>
          </div>

        [#else]
        [#-- Show the Enable Two-Factor form --]

          <form class="full">
            [@helpers.select name="method" id="select-method" options=availableMethods label="${theme.message('select-two-factor-method')}" required=true/]
          </form>

          [#list availableMethods as method]
            <fieldset class="pb-3" data-method-instructions="${method}">
              [#-- Instructions --]
              [@instructions method/]
            </fieldset>
          [/#list]

          [#-- Enable Two Factor Form --]
          [@helpers.structuredForm id="two-factor-form" action="${request.contextPath}/account/two-factor/enable" method="POST"; section]
            [#if section == "formFields"]
              [@helpers.hidden name="client_id" /]
              [@helpers.hidden name="tenantId" /]
              [@helpers.hidden name="email" /]
              [@helpers.hidden name="method" /]
              [@helpers.hidden name="mobilePhone" /]
              [#-- 'secret' and 'twoFactorSecretBase32' are required for authenticator. --]
              [@helpers.hidden name="secret" /]
              [@helpers.hidden name="secretBase32Encoded" /]

              [@helpers.input type="text" name="code" id="verification-code" label=theme.message("verification-code") placeholder="${theme.message('{placeholder}two-factor-code')}" autocapitalize="none"  autocomplete="one-time-code" autocorrect="off" required=true/]
            [#elseif section == "buttons"]
              [@helpers.button icon="save" text=theme.message("enable")/]
            [/#if]
          [/@helpers.structuredForm]
        [/#if]

      [/@helpers.accountPanelFull]
    [/@helpers.accountMain]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]

  [/@helpers.body]
[/@helpers.html]
