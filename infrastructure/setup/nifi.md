# Nifi

## Steps

1. Delete Nifi services:
 
```
kubectl delete -f nifi/kubernetes/daf_nifi.yml
```

2. Create a service and check if it is created:

```
kubectl create -f daf_nifi.yml
```
**NB**:controlla che il file /etc/krb5.conf non contenga la riga:
 ```   includedir /path/to/file/krb5.include.d ```