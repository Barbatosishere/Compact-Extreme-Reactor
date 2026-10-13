param(
    [int]$Port = 25575,
    [string]$Password = "cer",
    [string]$ServerHost = "127.0.0.1",
    [string[]]$Commands = @("list")
)

# 简单 RCON 协议实现（支持多命令）。
# Forge 1.20.1 的 RCON 鉴权成功返回空 type-2 包（不是 "OK"），按 requestId 匹配判断成功。
# RCON 协议全程使用小端序（little-endian）。

$ErrorActionPreference = "Stop"

# Git Bash parses UTF-8; Windows PowerShell otherwise uses the active ANSI code
# page when launched without a console, which can make grep treat replies as binary.
$rconUtf8 = New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding = $rconUtf8
$OutputEncoding = $rconUtf8

function Send-Packet($stream, [byte[]]$body) {
    $len = 8 + $body.Length + 2
    $packet = New-Object byte[] (4 + $len)
    # 全部小端
    [Array]::Copy([BitConverter]::GetBytes($len), 0, $packet, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]$script:reqId), 0, $packet, 4, 4)
    [Array]::Copy([BitConverter]::GetBytes([int]$script:packetType), 0, $packet, 8, 4)
    [Array]::Copy($body, 0, $packet, 12, $body.Length)
    $packet[12 + $body.Length] = 0
    $packet[12 + $body.Length + 1] = 0
    $stream.Write($packet, 0, $packet.Length)
    $stream.Flush()
}

function Read-Bytes($stream, $count) {
    $result = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $n = $stream.Read($result, $read, $count - $read)
        if ($n -le 0) { throw "Truncated RCON packet" }
        $read += $n
    }
    return ,$result
}

function Read-Packet($stream) {
    $lenBytes = Read-Bytes $stream 4
    if ($lenBytes.Length -lt 4) { return $null }
    $len = [BitConverter]::ToInt32($lenBytes, 0)  # 小端
    if ($len -lt 10 -or $len -gt 65536) { return $null }
    $full = Read-Bytes $stream $len
    if ($full.Length -lt $len) { return $null }
    $reqId = [BitConverter]::ToInt32($full, 0)  # 小端
    $type  = [BitConverter]::ToInt32($full, 4)  # 小端
    if ($full[$len - 2] -ne 0 -or $full[$len - 1] -ne 0) { throw "Invalid RCON terminator" }
    $text = [Text.Encoding]::UTF8.GetString($full, 8, $len - 10)
    return @{ ReqId = $reqId; Type = $type; Text = $text }
}

$script:reqId = 0
$script:packetType = 0

try {
    $client = New-Object Net.Sockets.TcpClient
    $client.Connect($ServerHost, $Port)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 10000
    $stream.WriteTimeout = 10000

    # 鉴权
    $script:reqId = 1
    $script:packetType = 3
    Send-Packet $stream ([Text.Encoding]::UTF8.GetBytes($Password))
    $resp = Read-Packet $stream
    if ($null -eq $resp) {
        Write-Output "AUTH_FAILED: no response"
        exit 1
    }
    if ($resp.ReqId -ne $script:reqId -or $resp.Type -ne 2) {
        Write-Output "AUTH_FAILED: wrong password"
        exit 1
    }

    foreach ($cmd in $Commands) {
        $script:reqId = $script:reqId + 1
        $script:packetType = 2
        Send-Packet $stream ([Text.Encoding]::UTF8.GetBytes($cmd))
        $resp = Read-Packet $stream
        if ($null -eq $resp) {
            Write-Output "NO_RESPONSE: $cmd"
            throw "No command response"
        }
        if ($resp.ReqId -ne $script:reqId -or $resp.Type -ne 0) { throw "Unexpected RCON response" }
        $commandId = $script:reqId
        $responseText = New-Object Text.StringBuilder
        [void]$responseText.Append($resp.Text)
        $script:reqId = $script:reqId + 1
        $script:packetType = 0
        Send-Packet $stream ([byte[]]@())
        while ($true) {
            $resp = Read-Packet $stream
            if ($null -eq $resp -or $resp.Type -ne 0) { throw "Invalid RCON response packet" }
            if ($resp.ReqId -eq $script:reqId) {
                if ($resp.Text -ne 'Unknown request 0') { throw "Invalid RCON response delimiter" }
                break
            }
            if ($resp.ReqId -ne $commandId) { throw "Unexpected RCON response ID" }
            [void]$responseText.Append($resp.Text)
        }
        Write-Output "> $cmd"
        Write-Output $responseText.ToString()
    }
    $stream.Close()
    $client.Close()
} catch {
    Write-Output "RCON_ERROR: $_"
    exit 1
}
finally {
    if ($null -ne $stream) { $stream.Dispose() }
    if ($null -ne $client) { $client.Dispose() }
}
