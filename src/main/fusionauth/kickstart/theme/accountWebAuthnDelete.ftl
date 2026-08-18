[#ftl/]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="credential" type="io.fusionauth.domain.WebAuthnCredential" --]

[#import "../../_helpers.ftl" as helpers/]

[#macro row name value]
<tr>
  <td class="top"> ${name} ${theme.message("propertySeparator")} </td>
  <td> ${value} </td>
</tr>
[/#macro]

[@helpers.html]
  [@helpers.head title=theme.message("account-webauthn-delete-page-title")]
    [#-- Custom head/script code goes here --]
  [/@helpers.head]
  [@helpers.body]

    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.accountMain rowClass="row center" colClass="col-xs-12 col-sm-12 col-md-10 col-lg-8" actionURL="/account/webauthn/" actionText=theme.message("go-back")]
      [@helpers.accountPanelFull title="${theme.message('delete-webauthn-passkey')}"]
        <fieldset>
          <p class="mb-3">${theme.message("{description}delete-webauthn-passkey")}</p>

          <table class="properties text-xs">
            <tbody>
              [@row name=theme.message("name") value=helpers.display(credential, "displayName") /]
              [@row name=theme.message("identifier") value=helpers.display(credential, "name") /]
              [@row name=theme.message("id") value=helpers.display(credential, "id") /]
              [@row name=theme.message("created") value=theme.formatZoneDateTime(credential.insertInstant, theme.message('date-time-format'), zoneId) /]
              [@row name=theme.message("last-used") value=theme.formatZoneDateTime(credential.lastUseInstant, theme.message('date-time-format'), zoneId) /]
              [@row name=theme.message("relying-party-id") value=helpers.display(credential, "relyingPartyId") /]
              [@row name=theme.message("signature-count") value=helpers.display(credential, "signCount") /]
            </tbody>
          </table>
        </fieldset>

        [@helpers.structuredForm id="webauthn-form" action="${request.contextPath}/account/webauthn/delete" method="POST"; section]
          [#if section == "formFields"]
            [@helpers.hidden name="id"/]
            [@helpers.hidden name="client_id"/]
            [@helpers.hidden name="tenantId"/]
          [#elseif section == "buttons"]
            [@helpers.button style="delete" text=theme.message("delete")/]
          [/#if]
        [/@helpers.structuredForm]
      [/@helpers.accountPanelFull]
    [/@helpers.accountMain]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]


