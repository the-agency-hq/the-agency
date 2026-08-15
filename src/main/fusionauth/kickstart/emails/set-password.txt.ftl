Hi ${(user.username)!'there'},

You've been invited to The Agency. Visit the link below to set a password and finish creating your account.

${tenant.issuer}/password/change/${changePasswordId}?tenantId=${user.tenantId}&client_id=7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742&redirect_uri=${(((user.data.url)!'http://localhost:8080') + '/oidc/return')?url('UTF-8')}&response_type=code

You're receiving this because someone invited you to The Agency. If you weren't expecting this, you can safely ignore this email.

— The Agency
