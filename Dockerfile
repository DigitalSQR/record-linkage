FROM opensearchproject/opensearch:1.2.4
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch https://github.com/DigitalSQR/record-linkage/releases/download/v1.2.4.0/record-linkage.zip
