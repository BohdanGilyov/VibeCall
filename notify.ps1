param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('incoming', 'connected', 'ended')]
    [string]$Event,

    [Parameter(Position = 1)]
    [string]$Caller = '',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Extra
)

$callerText = (@($Caller) + @($Extra)) -join ' '

try {
    $client = New-Object System.Net.Sockets.UdpClient

    $message = [System.Text.Encoding]::UTF8.GetBytes(
        "$Event|$callerText"
    )

    [void]$client.Send(
        $message,
        $message.Length,
        '127.0.0.1',
        47621
    )

    $client.Close()
} catch {
}