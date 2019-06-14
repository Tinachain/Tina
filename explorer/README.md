
### Tinachain-tx
Tinachain-tx.js Ŀ����Ϊ�����û��ǳ�������ڿͻ��ˣ�Ǯ���ˣ������ף����ڱ��ؽ���ǩ���󽫽��׷��͵�Tianchain�ϡ�
����Tinachain�����齫��������Ĭ�϶˿�8545���⿪���������Ҫ��װNginx���н���������ת�����ڿ�����Nginx�Ͻ����ض�ҵ��Ŀ�����

### ��װNginx
* gcc ��װ
    yum install gcc-c++

* PCRE pcre-devel ��װ
    yum install -y pcre pcre-devel

* zlib ��װ
    yum install -y zlib zlib-devel

* OpenSSL ��װ
    yum install -y openssl openssl-devel

* ����Nginx
    wget -c https://nginx.org/download/nginx-1.10.1.tar.gz

* ��ѹ
    tar -zxvf nginx-1.10.1.tar.gz
    cd nginx-1.10.1

* ����
    ./configure

* ��װ
    make && make install


### ����Nginx
* cd /usr/local/nginx/conf
* vi nginx.conf

    server {
        listen      9001 ;
        location / {
                proxy_pass   http://127.0.0.1:8545;
                add_header 'Access-Control-Allow-Credentials' 'true';
                add_header Access-Control-Allow-Origin *;
                add_header Access-Control-Allow-Headers *;
        }
    }