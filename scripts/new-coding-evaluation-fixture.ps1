param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("minimal-full-stack", "failed-test-minimal-fix", "split-frontend-backend", "existing-repository-feature", "long-task-recovery")]
    [string]$Scenario,
    [Parameter(Mandatory = $true)]
    [string]$WorkspaceRoot
)

$ErrorActionPreference = "Stop"

function Assert-SafeFixtureRoot {
    param([string]$Path)
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $driveRoot = [System.IO.Path]::GetPathRoot($resolved)
    if ([string]::IsNullOrWhiteSpace($resolved) -or $resolved -eq $driveRoot) {
        throw "WorkspaceRoot must be a dedicated directory, not a drive root."
    }
    return $resolved.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
}

function Write-FixtureFile {
    param([string]$Base, [string]$RelativePath, [string]$Content)
    $target = Join-Path $Base $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    [System.IO.File]::WriteAllText($target, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Initialize-FixtureGitRepository {
    param([string]$Path)

    # Every evaluation fixture needs a clean, committed baseline. Otherwise an
    # agent can make the right edit but git.diff has no tracked change to show.
    & git -C $Path init --quiet
    if ($LASTEXITCODE -ne 0) { throw "Could not initialize the fixture Git repository." }
    & git -C $Path add --all
    if ($LASTEXITCODE -ne 0) { throw "Could not stage the fixture baseline." }
    & git -C $Path -c user.name="Agent Studio Evaluation" -c user.email="evaluation@localhost" commit --quiet -m "Initialize evaluation fixture"
    if ($LASTEXITCODE -ne 0) { throw "Could not commit the fixture baseline." }
}

# Only creates a new timestamped child directory. It never deletes or overwrites an existing project.
$safeRoot = Assert-SafeFixtureRoot $WorkspaceRoot
New-Item -ItemType Directory -Force -Path $safeRoot | Out-Null
$fixture = Join-Path $safeRoot "agent-studio-$Scenario-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Path $fixture -ErrorAction Stop | Out-Null

# 这个标记只用于评测脚本的本地安全检查。它不包含密钥、绝对路径或用户数据，
# 也不代表该目录受到操作系统级沙箱保护；执行不可信代码仍应使用低权限节点。
# Get-Date 的 Format 参数直接返回字符串，避免在 Windows PowerShell 5.1 中对链式方法调用产生兼容差异。
$fixtureCreatedAt = Get-Date -Format "o"
$fixtureMarkerLines = @(
    "scenario=$Scenario",
    "createdAt=$fixtureCreatedAt",
    "formatVersion=1"
)
$fixtureMarkerContent = $fixtureMarkerLines -join [Environment]::NewLine
Write-FixtureFile $fixture ".agent-studio-evaluation-fixture" $fixtureMarkerContent

# These files are deliberately unfinished. The evaluated Agent must implement and verify the solution itself.
switch ($Scenario) {
    "minimal-full-stack" {
        Write-FixtureFile $fixture "SCENARIO.md" (@(
            "# Minimal full-stack todo app",
            "Create a Java backend and an HTML/JavaScript frontend in this directory.",
            "Implement GET /api/tasks, POST /api/tasks, and PATCH /api/tasks/{id}/toggle.",
            "The page must add a task and toggle completion.",
            "Run build and tests, start through a managed process, then verify browser interaction and API response.",
            "Finish with git.review and git.diff.") -join [Environment]::NewLine)
    }
    "failed-test-minimal-fix" {
        Write-FixtureFile $fixture "README.md" (@(
            "# Minimal fix scenario",
            'Run on the evaluation node: javac TaxCalculator.java TaxCalculatorTest.java && java TaxCalculatorTest',
            "The test fails initially. Change only TaxCalculator.java, rerun the same test, and inspect git.diff.") -join [Environment]::NewLine)
        Write-FixtureFile $fixture "TaxCalculator.java" (@(
            'public final class TaxCalculator {',
            '    private TaxCalculator() { }',
            '    // Intentional defect: this should calculate ten percent.',
            '    public static int taxFor(int amount) { return amount / 100; }',
            '}') -join [Environment]::NewLine)
        Write-FixtureFile $fixture "TaxCalculatorTest.java" (@(
            'public final class TaxCalculatorTest {',
            '    public static void main(String[] args) {',
            '        if (TaxCalculator.taxFor(250) != 25) throw new AssertionError("250 amount must produce 25 tax");',
            '        System.out.println("TaxCalculatorTest passed");',
            '    }',
            '}') -join [Environment]::NewLine)
    }
    "split-frontend-backend" {
        Write-FixtureFile $fixture "SCENARIO.md" (@(
            "# Split frontend/backend repository",
            "Use project.map or project.discover first, then inspect backend/ and frontend/ separately.",
            "Change the profile API to return displayName and role; update the frontend to request and display role.",
            "Build both modules, start the backend, and verify the page plus an observed API response.") -join [Environment]::NewLine)
        Write-FixtureFile $fixture "backend/README.md" "Create the Java backend here."
        Write-FixtureFile $fixture "frontend/README.md" "Create the browser client here."
    }
    "existing-repository-feature" {
        Write-FixtureFile $fixture "README.md" (@(
            "# Existing repository feature",
            "Read the existing code and test first.",
            "Add TaskRepository.completed() that returns only completed tasks, plus a focused test.",
            "Run the existing test first, then the focused test and the project test. Do not change unrelated files.") -join [Environment]::NewLine)
        Write-FixtureFile $fixture "TaskRepository.java" (@(
            'import java.util.List;',
            'public final class TaskRepository {',
            '    private final List<Task> tasks = List.of(new Task("write tests", true), new Task("ship feature", false));',
            '    public List<Task> all() { return tasks; }',
            '    public record Task(String title, boolean completed) { }',
            '}') -join [Environment]::NewLine)
        Write-FixtureFile $fixture "TaskRepositoryTest.java" (@(
            'public final class TaskRepositoryTest {',
            '    public static void main(String[] args) {',
            '        if (new TaskRepository().all().size() != 2) throw new AssertionError("fixture must contain two tasks");',
            '        System.out.println("TaskRepositoryTest passed");',
            '    }',
            '}') -join [Environment]::NewLine)
    }
    "long-task-recovery" {
        Write-FixtureFile $fixture "SCENARIO.md" (@(
            "# Long task recovery",
            "Create a small Java command-line project.",
            "Inspect before editing, then request an approval-protected write or process action.",
            "After approval, finish implementation, tests, and git.review.",
            "The evaluator expects RUN_WAITING_APPROVAL and RUN_RESUMED events; do not use full-access.") -join [Environment]::NewLine)
        Write-FixtureFile $fixture "App.java" (@(
            'public final class App {',
            '    public static void main(String[] args) {',
            '        System.out.println("replace this starter with the requested recovered workflow");',
            '    }',
            '}') -join [Environment]::NewLine)
    }
}

Write-FixtureFile $fixture ".gitignore" "*.class"
Initialize-FixtureGitRepository $fixture

Write-Host "Created isolated fixture: $fixture"
Write-Output $fixture
