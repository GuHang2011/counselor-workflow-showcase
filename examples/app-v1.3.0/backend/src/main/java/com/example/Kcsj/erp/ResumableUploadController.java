package com.example.Kcsj.erp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.example.Kcsj.erp.ErpStore.*;

@RestController
public class ResumableUploadController {
    private final ErpStore store;private final AuthService auth;private final AttachmentController attachments;
    @Value("${erp.upload-dir}") private String directory;
    private static final int CHUNK=1048576;
    public ResumableUploadController(ErpStore store,AuthService auth,AttachmentController attachments){this.store=store;this.auth=auth;this.attachments=attachments;}
    @PostMapping("/uploads") @Transactional public Map<String,Object> create(@RequestBody Map<String,Object> body,HttpServletRequest request)throws IOException{
        Map<String,Object> actor=auth.require(request);auth.writeAllowed(actor);String name=s(body,"name"),sha=s(body,"sha256"),source=s(body,"source");long size=number(body.get("size"));
        if(name.isEmpty()||name.length()>200||name.contains("/")||name.contains("\\")||name.contains("\n")||name.contains("\r"))throw new ApiException(400,"无效文件名");
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);long limit=Arrays.asList("mp4","webm","mov").contains(ext)?100L*CHUNK:Arrays.asList("jpg","jpeg","png").contains(ext)?10L*CHUNK:20L*CHUNK;
        if(!Arrays.asList("txt","csv","pdf","docx","xlsx","jpg","jpeg","png","mp4","mov","webm").contains(ext)||size<=0||size>limit||!sha.matches("[a-f0-9]{64}"))throw new ApiException(400,"文件类型、大小或校验值无效");
        if(!Arrays.asList("file","camera","screen").contains(source))throw new ApiException(400,"请选择上传来源");
        long active=0;for(Map<String,Object> upload:store.all("upload"))if(s(upload,"ownerId").equals(s(actor,"id"))&&!s(upload,"status").equals("completed")&&!s(upload,"status").equals("cancelled")&&Instant.parse(s(upload,"expiresAt")).isAfter(Instant.now())){active+=number(upload.get("size"));if(s(upload,"sha256").equals(sha)&&number(upload.get("size"))==size&&s(upload,"name").equals(name)&&s(upload,"source").equals(source))return ok(view(upload));}
        if(active+size>2L*1024*CHUNK)throw new ApiException(413,"未完成上传已达到个人空间上限，请取消不需要的上传");
        Map<String,Object> item=map("id",id(),"ownerId",s(actor,"id"),"name",name,"size",size,"sha256",sha,"source",source,"chunkBytes",CHUNK,"chunkCount",(size+CHUNK-1)/CHUNK,"chunks",new LinkedHashMap<>(),"status","uploading","createdAt",now(),"expiresAt",Instant.now().plusSeconds(86400).toString());store.save("upload",item);return ok(view(item));
    }
    @GetMapping("/uploads/{id}") public Map<String,Object> status(@PathVariable String id,HttpServletRequest request){return ok(view(owned(id,request)));}
    @PutMapping("/uploads/{id}/chunks/{index}") @Transactional public Map<String,Object> chunk(@PathVariable String id,@PathVariable int index,HttpServletRequest request)throws Exception{
        Map<String,Object> item=owned(id,request);active(item);int count=((Number)item.get("chunkCount")).intValue();if(index<0||index>=count)throw new ApiException(400,"分片编号无效");
        long expected=Math.min(CHUNK,number(item.get("size"))-(long)index*CHUNK);ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;while((n=request.getInputStream().read(bytes))!=-1){if(buffer.size()+n>expected)throw new ApiException(413,"分片大小不匹配");buffer.write(bytes,0,n);}if(buffer.size()!=expected)throw new ApiException(400,"分片不完整，请重传");
        String digest=hex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));Map<String,Object> chunks=chunks(item);String key=String.valueOf(index);if(chunks.containsKey(key)){if(!s(chunks,key).equals(digest))throw new ApiException(409,"分片已存在但内容不同");return ok(view(item));}
        Path root=folder(id);Files.createDirectories(root);Path part=root.resolve(key+".part");Files.write(part,buffer.toByteArray(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);chunks.put(key,digest);store.save("upload",item);return ok(view(item));
    }
    @PostMapping("/uploads/{id}/complete") @Transactional public Map<String,Object> complete(@PathVariable String id,HttpServletRequest request)throws Exception{
        Map<String,Object> item=owned(id,request);if(s(item,"status").equals("completed"))return ok(publicFile(store.get("file",s(item,"fileId"))));active(item);
        int count=((Number)item.get("chunkCount")).intValue();if(chunks(item).size()!=count)throw new ApiException(409,"还有分片未上传，请继续上传");Path root=folder(id),joined=root.resolve("assembled.bin");MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(OutputStream out=Files.newOutputStream(joined,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){for(int index=0;index<count;index++){byte[] bytes=Files.readAllBytes(root.resolve(index+".part"));if(!hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(s(chunks(item),String.valueOf(index))))throw new ApiException(409,"分片校验失败，请重新上传");digest.update(bytes);out.write(bytes);}}
        if(Files.size(joined)!=number(item.get("size"))||!hex(digest.digest()).equals(s(item,"sha256")))throw new ApiException(409,"文件完整性校验失败，请重新选择原文件");
        Map<String,Object> result=attachments.upload(new DiskPart(joined,s(item,"name")),s(item,"source"),request);@SuppressWarnings("unchecked") Map<String,Object> file=(Map<String,Object>)result.get("data");item.put("status","completed");item.put("fileId",s(file,"id"));store.save("upload",item);cleanupAfterCommit(root);return result;
    }
    @PostMapping("/uploads/{id}/cancel") @Transactional public Map<String,Object> cancel(@PathVariable String id,HttpServletRequest request)throws IOException{Map<String,Object> item=owned(id,request);if(!s(item,"status").equals("completed")){item.put("status","cancelled");store.save("upload",item);cleanupAfterCommit(folder(id));}return ok(view(item));}
    private void cleanupAfterCommit(Path root){org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){try{clear(root);}catch(IOException e){org.slf4j.LoggerFactory.getLogger(ResumableUploadController.class).warn("Temporary upload cleanup will retry");}}});}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000) @Transactional public void expireUploads(){store.lockWrites();for(Map<String,Object> item:store.all("upload")){if(Instant.parse(s(item,"expiresAt")).isBefore(Instant.now())){if(s(item,"status").equals("uploading")){item.put("status","expired");store.save("upload",item);}cleanupAfterCommit(folder(s(item,"id")));}}}
    private Map<String,Object> owned(String id,HttpServletRequest request){Map<String,Object> actor=auth.require(request);auth.writeAllowed(actor);Map<String,Object> item=store.get("upload",id);if(!s(item,"ownerId").equals(s(actor,"id")))throw new ApiException(403,"无权操作该上传");return item;}
    private void active(Map<String,Object> item){if(!s(item,"status").equals("uploading"))throw new ApiException(409,"上传已结束");if(Instant.parse(s(item,"expiresAt")).isBefore(Instant.now()))throw new ApiException(410,"上传已过期，请重新选择文件");}
    @SuppressWarnings("unchecked") private Map<String,Object> chunks(Map<String,Object> item){return (Map<String,Object>)item.get("chunks");}
    private Path folder(String id){if(!id.matches("[a-f0-9-]{36}"))throw new ApiException(400,"上传编号无效");return Paths.get(directory).toAbsolutePath().normalize().resolve("chunks").resolve(id);}
    private void clear(Path root)throws IOException{if(!Files.isDirectory(root))return;try(DirectoryStream<Path> files=Files.newDirectoryStream(root)){for(Path part:files){if(Files.isRegularFile(part)&&!Files.isSymbolicLink(part))Files.deleteIfExists(part);}}Files.deleteIfExists(root);}
    private Map<String,Object> view(Map<String,Object> item){Map<String,Object> out=new LinkedHashMap<>(item);out.remove("ownerId");out.remove("chunks");out.put("received",new ArrayList<>(chunks(item).keySet()));return out;}
    private Map<String,Object> publicFile(Map<String,Object> file){Map<String,Object> out=new LinkedHashMap<>(file);out.remove("storedName");out.remove("previewStoredName");out.remove("thumbnailStoredName");out.remove("processingError");return out;}
    private long number(Object value){try{return Long.parseLong(String.valueOf(value));}catch(Exception e){throw new ApiException(400,"文件大小无效");}}
    private String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format("%02x",b));return value.toString();}
    private Map<String,Object> ok(Object data){return map("code",0,"data",data);}
    private static class DiskPart implements MultipartFile {
        final Path path;final String name;DiskPart(Path path,String name){this.path=path;this.name=name;}
        public String getName(){return "file";}public String getOriginalFilename(){return name;}public String getContentType(){return "application/octet-stream";}public boolean isEmpty(){return getSize()==0;}public long getSize(){try{return Files.size(path);}catch(IOException e){throw new IllegalStateException(e);}}public byte[] getBytes()throws IOException{return Files.readAllBytes(path);}public InputStream getInputStream()throws IOException{return Files.newInputStream(path);}public void transferTo(File file)throws IOException{Files.copy(path,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
}
