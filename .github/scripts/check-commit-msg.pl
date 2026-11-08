#!/usr/bin/env perl
# Commit-message and identity check for HearCopy: the single source of these
# rules, used by .git/hooks/commit-msg, the local post-commit check and CI.
#
# Usage:
#   perl .github/scripts/check-commit-msg.pl --file <message-file>
#       Commit being created (commit-msg hook). Checks the message file,
#       every line including '#' lines, up to the `git commit -v` scissors
#       line. Identity comes from `git var GIT_AUTHOR_IDENT` and
#       `git var GIT_COMMITTER_IDENT`.
#   perl .github/scripts/check-commit-msg.pl --commit <rev>
#       Recorded commit (post-commit check, CI). Checks its full message and
#       its recorded author and committer.
#
# Rejects: CJK characters (including CJK punctuation and fullwidth forms),
# Co-authored-by / Claude-Session trailers, "Generated with/by" lines,
# the markers claude.ai/code, anthropic.com and noreply@anthropic, and any
# author or committer other than the expected identity.
#
# Exit status: 0 clean, 1 violation, 2 usage or git error.

use strict;
use warnings;

my $expected = 'Denny C. Gan <161263769+dennycgan@users.noreply.github.com>';

sub usage {
    print STDERR "usage: check-commit-msg.pl --file <message-file> | --commit <rev>\n";
    exit 2;
}

usage() unless @ARGV == 2;
my ($mode, $arg) = @ARGV;

my (@lines, $author, $committer, $where);

if ($mode eq '--file') {
    open(my $fh, '<:encoding(UTF-8)', $arg)
        or do { print STDERR "commit-msg: cannot read $arg\n"; exit 2 };
    while (my $line = <$fh>) {
        last if $line =~ /^# -+ >8 -+$/;
        push @lines, $line;
    }
    close($fh);
    $author = git_ident('GIT_AUTHOR_IDENT');
    $committer = git_ident('GIT_COMMITTER_IDENT');
    $where = '';
} elsif ($mode eq '--commit') {
    my ($sha) = git_lines('rev-parse', '--verify', '--quiet', "$arg^{commit}");
    defined $sha or do { print STDERR "commit-msg: not a commit: $arg\n"; exit 2 };
    @lines = git_lines('log', '-1', '--format=%B', $sha);
    ($author, $committer) = git_lines('log', '-1', '--format=%an <%ae>%n%cn <%ce>', $sha);
    $where = 'commit ' . substr($sha, 0, 12) . ': ';
} else {
    usage();
}

my $bad = 0;
my $n = 0;
for my $line (@lines) {
    $n++;
    $line =~ s/\r?\n\z//;
    if ($line =~ /[\p{Han}\p{Hiragana}\p{Katakana}\p{Hangul}\x{3000}-\x{303F}\x{FF00}-\x{FFEF}]/) {
        print STDERR "commit-msg: ${where}CJK character on line $n\n"; $bad = 1;
    }
    if ($line =~ /^\s*(co-authored-by|claude-session)\s*:/i) {
        print STDERR "commit-msg: ${where}attribution trailer on line $n\n"; $bad = 1;
    }
    if ($line =~ /^[^\p{L}\p{N}]*generated (with|by)\b/i) {
        print STDERR "commit-msg: ${where}\"Generated with/by\" line on line $n\n"; $bad = 1;
    }
    if ($line =~ m{claude\.ai/code|anthropic\.com|noreply\@anthropic}i) {
        print STDERR "commit-msg: ${where}AI attribution marker on line $n\n"; $bad = 1;
    }
}

for my $id (['author', $author], ['committer', $committer]) {
    my ($role, $value) = @$id;
    $value = '' unless defined $value;
    if ($value ne $expected) {
        print STDERR "commit-msg: ${where}$role is '$value', expected '$expected'\n";
        $bad = 1;
    }
}

if ($bad) {
    print STDERR "commit-msg: ${where}commit rejected.\n";
    exit 1;
}
exit 0;

# Runs git with a list of arguments (no shell) and returns its output lines,
# decoded as UTF-8. Exits 2 when git fails.
sub git_lines {
    my @args = @_;
    open(my $fh, '-|:encoding(UTF-8)', 'git', @args)
        or do { print STDERR "commit-msg: cannot run git @args\n"; exit 2 };
    my @out = <$fh>;
    close($fh);
    if ($? != 0) {
        return () if $args[0] eq 'rev-parse';
        print STDERR "commit-msg: git @args failed\n";
        exit 2;
    }
    chomp @out;
    return @out;
}

# Identity of the commit being created, without the timestamp.
sub git_ident {
    my ($var) = @_;
    my ($ident) = git_lines('var', $var);
    $ident = '' unless defined $ident;
    $ident =~ s/ \d+ [-+]\d+\z//;
    return $ident;
}
