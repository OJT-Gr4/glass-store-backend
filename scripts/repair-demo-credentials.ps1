param([switch]$Apply)
. "$PSScriptRoot/common.ps1"
Import-GlassStoreEnvironment
$migrationPath=Join-Path $script:BackendRoot 'docker/mysql/migrations/20261106-repair-demo-password-hash.sql'
$migration=[IO.File]::ReadAllText($migrationPath,[Text.Encoding]::UTF8)
$where=[regex]::Match($migration,'(?s)WHERE password_hash.*?(?=;\s*SELECT)').Value
if(-not $where){throw 'Cannot identify the scoped migration predicate.'}
$targets=@(Invoke-GlassStoreSql ('SELECT id,username,email,password_hash FROM db_identity.users '+$where+';'))
Write-Host "Known historical demo credentials eligible for repair: $($targets.Count)"
if(-not $Apply){Write-Host 'Read-only check. Add -Apply to repair only these exact legacy fixtures.';return}
if(-not $targets.Count){Write-Host 'No known legacy fixtures found. No password changed.';return}
$backupDir=Join-Path $script:RuntimeRoot 'backups'
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
$backupPath=Join-Path $backupDir ('demo-credentials-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')+'.tsv')
[IO.File]::WriteAllLines($backupPath,$targets,[Text.UTF8Encoding]::new($false))
Invoke-GlassStoreSql $migration
Write-Host 'Repaired only the canonical demo accounts with the exact incorrect old hash. Custom passwords were not reset.'
Write-Host "Private backup location: $backupPath"
