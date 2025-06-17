Name:           apache-sshd
Version:        1.0
Release:        1
Epoch:          0
Summary:        Apache Mina SSHD
Group:          Development/Java
License:        ASL 2.0
URL:            https://github.com/apache/mina-sshd
Source0:        sshd-1.0-project-sources.zip

BuildArch:      noarch

%description
Apache SSHD is a 100% pure java library to support the SSH protocols on both the client and server side.

%prep
%setup -n apache-sshd-1.0

%files
%defattr(0644,root,root,0755)
%doc README.md

%changelog
* Wed Apr 09 2025 N Cross <ncross@redhat.com> - 1.0-1
- Wrapper build
