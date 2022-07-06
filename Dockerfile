FROM opensearchproject/opensearch:2.0.1
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch https://github.com/DigitalSQR/record-linkage/releases/download/v2.0.1.0/record-linkage.zip
