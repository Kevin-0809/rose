param(
    [Parameter(Mandatory = $true)]
    [string]$JasyptEncryptorPassword,

    [string]$JarPath = "target/rose-0.0.1-SNAPSHOT.jar"
)

$env:JASYPT_ENCRYPTOR_PASSWORD = $JasyptEncryptorPassword

java -jar $JarPath
