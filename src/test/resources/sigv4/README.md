# SigV4 test vectors

Every directory except `bedrock-converse-session-token` is a header-signing case from the AWS
Signature Version 4 test suite as published in
[awslabs/aws-c-auth](https://github.com/awslabs/aws-c-auth/tree/main/tests/aws-signing-test-suite/v4)
(Apache License 2.0), commit `3d29adc`. Only the header-signing files are kept. Cases that
depend on path normalization (`get-relative-*`, `get-slash*`) and on adding the session token
after signing (`post-sts-header-after`) are left out because Bedrock calls never need them.

`bedrock-converse-session-token` is a Converse call with temporary credentials and a model id
holding `:`, which shows the double encoding of the path. Its expected values come from
botocore's `SigV4Auth`.

| File | Content |
|---|---|
| `context.json` | Credentials, region, service and time |
| `request.txt` | The request as sent |
| `header-canonical-request.txt` | Expected canonical request |
| `header-string-to-sign.txt` | Expected string to sign |
| `header-signature.txt` | Expected signature |

The credentials are the published AWS examples, not real keys.
