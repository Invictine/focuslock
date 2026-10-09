#define MyAppName "Void"
#ifndef MyAppVersion
  #define MyAppVersion "1.3.0"
#endif
#define MyAppPublisher "Void"
#define MyAppExeName "Void.exe"

[Setup]
AppId={{6E736CF3-75E7-4C0B-8D30-87003130D550}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\Void
DefaultGroupName=Void
UninstallDisplayName=Void
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\outputs
OutputBaseFilename=Void-Setup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
UninstallFilesDir={app}\uninstall
CloseApplications=no
AppMutex=Local\Void.FocusShell
MinVersion=10.0.14393
SetupLogging=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Files]
Source: "..\work\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Void"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Void"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Code]
const
  RunKey = 'Software\Microsoft\Windows\CurrentVersion\Run';
  RunValue = 'Void';

function ExpectedRunCommand(): string;
begin
  Result := '"' + ExpandConstant('{app}\{#MyAppExeName}') + '"';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Existing: string;
begin
  if CurUninstallStep = usUninstall then begin
    if RegQueryStringValue(HKCU, RunKey, RunValue, Existing) and
       (CompareText(Existing, ExpectedRunCommand()) = 0) then
      RegDeleteValue(HKCU, RunKey, RunValue);
  end;
end;
