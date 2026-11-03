[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$BaseUrl,
    [Parameter(Mandatory)][string]$SandboxHost,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9-]{36}$')][string]$RentalId,
    [Parameter(Mandatory)][ValidateSet('move-in','early-exit')][string]$Kind,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9-]{36}$')][string]$SigningContractId,
    [Parameter(Mandatory)][ValidateSet('Owner','Tenant')][string]$Role,
    [switch]$Initiate,
    [string]$RequestId,
    [int]$PollSeconds = 120
)
$ErrorActionPreference = 'Stop'
# This script is an operator-run sandbox kit. It is never invoked by the application.
if ($BaseUrl.Host -ne $SandboxHost -or $BaseUrl.Query -or $BaseUrl.Fragment) { throw 'Base URL must match the explicitly selected sandbox host.' }
if ($BaseUrl.Scheme -ne 'https' -and $BaseUrl.Host -notin @('localhost','127.0.0.1')) { throw 'Use HTTPS for a remote sandbox.' }
$tokenVariable = 'HS_SMARTCA_TEST_' + $Role.ToUpperInvariant() + '_TOKEN'
$serialVariable = 'HS_SMARTCA_TEST_' + $Role.ToUpperInvariant() + '_SERIAL'
$token = [Environment]::GetEnvironmentVariable($tokenVariable)
if ([string]::IsNullOrWhiteSpace($token)) { throw "Set $tokenVariable in this terminal. Do not paste tokens into chat or commit them." }
$headers = @{ Authorization = 'Bearer ' + $token }
$api = $BaseUrl.AbsoluteUri.TrimEnd('/') + '/api/v1'
function Read-Core([string]$Path) { (Invoke-RestMethod -Method Get -Uri ($api + $Path) -Headers $headers).result }
function Write-Core([string]$Path, [object]$Body) { (Invoke-RestMethod -Method Post -Uri ($api + $Path) -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 8)).result }
$source = Read-Core "/rental-requests/$RentalId/workflow/$Kind"
$documents = @(Read-Core "/rental-requests/$RentalId/workflow/$Kind/documents")
$binding = $documents | Where-Object { $_.signingContractId -eq $SigningContractId }
if (@($binding).Count -ne 1 -or -not $binding.current) { throw 'The selected PDF does not match the current workflow source.' }
$contract = Read-Core "/contracts/$SigningContractId"
if ($contract.agreementKind -ne 'WORKFLOW' -or $contract.parentContractId -ne $source.contractId) { throw 'This must be a separate workflow document belonging to the selected original lease.' }
$state = Read-Core "/contracts/$SigningContractId/signatures/state"
if ($state.signatureMode -ne 'SMARTCA' -or -not $state.smartCaEnabled) { throw 'SmartCA sandbox is not enabled. No request was initiated.' }
Write-Host "Verified current $Kind PDF, source revision $($source.revision), document state $($contract.status)."
if (-not $Initiate -and -not $RequestId) {
    Write-Host 'Read-only preflight passed. Review the PDF, then rerun with -Initiate for this signer. Tokens/certificate serials come from environment variables.'
    return
}
if ($Initiate) {
    if ($RequestId) { throw 'Use either -Initiate or -RequestId to reconcile an existing transaction.' }
    $expectedStatus = if ($Role -eq 'Owner') { 'DRAFT' } else { 'TENANT_SIGNATURE_PENDING' }
    if ($contract.status -ne $expectedStatus) { throw "Expected $expectedStatus for this signer. Reconcile existing requests first." }
    $serial = [Environment]::GetEnvironmentVariable($serialVariable)
    $certificates = @(Read-Core "/contracts/$SigningContractId/signatures/certificates")
    if (-not $serial -or -not ($certificates | Where-Object { $_.serialNumber -eq $serial })) { throw "Set $serialVariable to a certificate returned for this sandbox signer." }
    $answer = Read-Host 'After personally reviewing this PDF, type SIGN SANDBOX to send one real sandbox signing request'
    if ($answer -cne 'SIGN SANDBOX') { throw 'Signing was not requested.' }
    $request = Write-Core "/contracts/$SigningContractId/signatures/initiate" @{certificateSerial=$serial;consent=$true}
    $RequestId = $request.id
    Write-Host "Request created: $RequestId. Confirm or reject it in the sandbox SmartCA app."
}
if ($RequestId -notmatch '^[a-fA-F0-9-]{36}$') { throw 'Expected the UUID of a real existing signature request.' }
$deadline = [DateTimeOffset]::UtcNow.AddSeconds([Math]::Clamp($PollSeconds, 1, 600))
do {
    $request = Write-Core "/contracts/$SigningContractId/signatures/$RequestId/refresh" @{}
    Write-Host "Request $RequestId : $($request.status)"
    if ($request.status -in @('SIGNED','REJECTED','EXPIRED','FAILED','CANCELLED')) { break }
    Start-Sleep -Seconds 3
} while ([DateTimeOffset]::UtcNow -lt $deadline)
$current = Read-Core "/rental-requests/$RentalId/workflow/$Kind"
$after = @(Read-Core "/rental-requests/$RentalId/workflow/$Kind/documents") | Where-Object { $_.signingContractId -eq $SigningContractId }
[pscustomobject]@{ requestId=$RequestId; signatureStatus=$request.status; sourceStatus=$current.status; sourceRevision=$current.revision; signedAt=$after.signedAt; current=$after.current }
if ($request.status -ne 'SIGNED') { throw 'Signature is not verified as SIGNED. Reconcile this request; do not blindly initiate another.' }
