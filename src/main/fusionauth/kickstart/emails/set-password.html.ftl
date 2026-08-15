<!DOCTYPE html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="X-UA-Compatible" content="IE=edge">
  <title>Set your password for The Agency</title>
</head>
<body style="margin:0; padding:0; background-color:#f8fafc;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f8fafc;">
    <tr>
      <td align="center" style="padding:32px 12px;">
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px; max-width:600px;">

          <tr>
            <td style="height:4px; line-height:4px; font-size:4px; background-color:#06b6d4; border-top-left-radius:8px; border-top-right-radius:8px;">&nbsp;</td>
          </tr>

          <tr>
            <td style="background-color:#ffffff; border-left:1px solid #e2e8f0; border-right:1px solid #e2e8f0; padding:28px 32px 4px 32px; font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
              <span style="font-size:18px; font-weight:700; letter-spacing:0.2em; color:#0f172b;">THE&nbsp;AGENCY</span>
            </td>
          </tr>

          <tr>
            <td style="background-color:#ffffff; border-left:1px solid #e2e8f0; border-right:1px solid #e2e8f0; border-bottom:1px solid #e2e8f0; border-bottom-left-radius:8px; border-bottom-right-radius:8px; padding:12px 32px 36px 32px; font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; color:#0f172b; font-size:16px; line-height:1.6;">
              <p style="margin:0 0 16px 0;">Hi ${(user.username)!'there'},</p>
              <p style="margin:0 0 16px 0;">You've been invited to The Agency. Click the button below to set a password and finish creating your account.</p>
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:24px 0;">
                <tr>
                  <td align="center" bgcolor="#0e7490" style="border-radius:8px;">
                    <a href="${tenant.issuer}/password/change/${changePasswordId}?tenantId=${user.tenantId}&client_id=7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742&redirect_uri=${'http://localhost:8080/oidc/return'?url('UTF-8')}&response_type=code" target="_blank" style="display:inline-block; padding:12px 28px; font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:15px; font-weight:600; color:#ffffff; text-decoration:none; border-radius:8px;">Set my password</a>
                  </td>
                </tr>
              </table>
              <p style="margin:16px 0 0 0; font-size:13px; line-height:1.5; color:#62748e;">If the button doesn't work, copy and paste this link into your browser:</p>
              <p style="margin:6px 0 0 0; font-size:13px; line-height:1.5; word-break:break-all;"><a href="${tenant.issuer}/password/change/${changePasswordId}?tenantId=${user.tenantId}&client_id=7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742&redirect_uri=${'http://localhost:8080/oidc/return'?url('UTF-8')}&response_type=code" target="_blank" style="color:#0e7490; text-decoration:underline;">${tenant.issuer}/password/change/${changePasswordId}?tenantId=${user.tenantId}&client_id=7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742&redirect_uri=${'http://localhost:8080/oidc/return'?url('UTF-8')}&response_type=code</a></p>
            </td>
          </tr>

          <tr>
            <td style="padding:20px 32px 0 32px; font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; color:#62748e; font-size:13px; line-height:1.5;">
              You're receiving this because someone invited you to The Agency. If you weren't expecting this, you can safely ignore this email.
            </td>
          </tr>

        </table>
      </td>
    </tr>
  </table>
</body>
</html>
