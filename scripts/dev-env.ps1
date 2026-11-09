# Bu betik, bu makinedeki araclari mevcut PowerShell oturumuna ekler.
$jdk = 'C:\Users\Toxido\tools\jdk-21'
$mvn = 'C:\Users\Toxido\tools\maven'
$git = 'C:\Users\Toxido\tools\PortableGit'
$py  = 'C:\Users\Toxido\AppData\Local\Programs\Python\Python312'

$env:JAVA_HOME = $jdk
$env:MAVEN_HOME = $mvn
$env:Path = "$jdk\bin;$mvn\bin;$git\cmd;$py;$py\Scripts;" + $env:Path
