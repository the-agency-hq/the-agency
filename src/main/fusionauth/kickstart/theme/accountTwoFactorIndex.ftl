[#ftl/]
[#setting url_escaping_charset="UTF-8"]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="user" type="io.fusionauth.domain.User" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]

[#import "../../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("account-two-factor-index-page-title")/]
  [@helpers.body]

    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.accountMain rowClass="row center" colClass="col-xs-12 col-sm-12 col-md-10 col-lg-8" actionURL="/account/" actionText=theme.message("go-back")]
        [@helpers.accountPanelFull title="${theme.message('two-factor-authentication')}"]
          <div class="mb-4">${theme.message("{description}two-factor-authentication")}</div>

           <fieldset>

             <table class="hover text-left w-full mb-8 text-sm">
               <thead>
                <tr class="border-b border-slate-400">
                <th>${theme.message("method")}</th>
                <th>${theme.message("value")}</th>
                <th class="action">${theme.message("action")}</th>
                </tr>
              </thead>
               <tbody>
                 [#list user.twoFactor.methods as method]
                   <tr>
                     <td class="py-2"> ${theme.message(method.method)} </td>
                     <td class="py-2">
                       [#if method.method == "email"]${helpers.display(method, "email")}
                       [#elseif method.method == "sms"]${helpers.display(method, "mobilePhone")}
                       [#else]&ndash;
                       [/#if]
                     </td>
                     <td class="action py-2">
                       [@helpers.buttonLink type="delete" size="sm" text=theme.message('disable') href="${request.contextPath}/account/two-factor/disable?client_id=${client_id}&methodId=${method.id?url}&tenantId=${tenantId!''}"?no_esc][/@helpers.buttonLink]
[#--                       <a class="small-square gray button ml-2 pr-0" href="${request.contextPath}/account/two-factor/disable?client_id=${client_id}&methodId=${method.id?url}&tenantId=${tenantId!''}" data-tooltip="${theme.message('disable')}"> <i class="fa fa-minus"></i> </a>--]
                     </td>
                   </tr>
                 [#else]
                 <tr>
                    <td colspan="4" class="pt-2">${theme.message("no-two-factor-methods-configured")}</td>
                 </tr>
                 [/#list]
               </tbody>
             </table>

             <div class="form-row mt-3">
               [@helpers.buttonLink text=theme.message("add-two-factor") href="${request.contextPath}/account/two-factor/enable?client_id=${client_id}&tenantId=${tenantId!''}"?no_esc][/@helpers.buttonLink]
             </div>

           </fieldset>

      [/@helpers.accountPanelFull]
    [/@helpers.accountMain]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
