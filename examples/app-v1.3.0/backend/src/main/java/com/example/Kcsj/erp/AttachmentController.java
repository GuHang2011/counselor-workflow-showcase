package com.example.Kcsj.erp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URLEncoder;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import static com.example.Kcsj.erp.ErpStore.*;

@RestController
public class AttachmentController {
    private final ErpStore store;private final AuthService auth;private final BusinessService business;private final MediaProcessingService processing;private final List<AttachmentAccess> extraAccess;private final MediaQueue queue;private final AttachmentWatermark watermark;
    @Value("${erp.upload-dir}") private String directory;
    public AttachmentController(ErpStore store,AuthService auth,BusinessService business,MediaProcessingService processing,List<AttachmentAccess> extraAccess,MediaQueue queue,AttachmentWatermark watermark){this.store=store;this.auth=auth;this.business=business;this.processing=processing;this.extraAccess=extraAccess;this.queue=queue;this.watermark=watermark;}
    private boolean canRead(Map<String,Object> actor,Map<String,Object> file){if(business.viewFile(actor,file))return true;for(AttachmentAccess access:extraAccess)if(access.canRead(actor,s(file,"id")))return true;return false;}
    private Map<String,Object> publicFile(Map<String,Object> file){Map<String,Object> out=new LinkedHashMap<>(file);out.remove("storedName");out.remove("previewStoredName");out.remove("processingError");return out;}
    @GetMapping("/files/capabilities") public Map<String,Object> capabilities(HttpServletRequest request){auth.require(request);return map("code",0,"data",processing.capabilities());}
    @GetMapping("/files") public Map<String,Object> list(HttpServletRequest request){
        Map<String,Object> actor=auth.require(request);List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> f:store.all("file"))if(canRead(actor,f))out.add(publicFile(f));out.sort(Comparator.comparing(f->s(f,"createdAt"),Comparator.reverseOrder()));return map("code",0,"data",out);
    }
    @PostMapping("/files") @Transactional public Map<String,Object> upload(@RequestParam("file") MultipartFile file,@RequestParam(defaultValue="file") String source,HttpServletRequest request) throws IOException {
        Map<String,Object> actor=auth.require(request);auth.writeAllowed(actor);
        if(!Arrays.asList("file","camera","screen").contains(source))throw new ApiException(400,"未知上传来源");
        String original=file.getOriginalFilename();if(original==null||original.trim().isEmpty()||original.length()>200||original.contains("/")||original.contains("\\")||original.contains("\r")||original.contains("\n"))throw new ApiException(400,"文件名无效");
        int dot=original.lastIndexOf('.');if(dot<0)throw new ApiException(400,"文件缺少扩展名");String ext=original.substring(dot+1).toLowerCase(Locale.ROOT);
        String mime;long max;
        switch(ext){
            case "jpg":case "jpeg":mime="image/jpeg";max=10*1024*1024;break;
            case "png":mime="image/png";max=10*1024*1024;break;
            case "pdf":mime="application/pdf";max=20*1024*1024;break;
            case "txt":mime="text/plain;charset=UTF-8";max=20*1024*1024;break;
            case "csv":mime="text/csv;charset=UTF-8";max=20*1024*1024;break;
            case "docx":mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";max=20*1024*1024;break;
            case "xlsx":mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";max=20*1024*1024;break;
            case "mp4":mime="video/mp4";max=100*1024*1024;break;
            case "mov":mime="video/quicktime";max=100*1024*1024;break;
            case "webm":mime="video/webm";max=100*1024*1024;break;
            default:throw new ApiException(400,"仅支持 TXT、CSV、PDF、JPG、PNG、DOCX、XLSX、MP4、WEBM、MOV");
        }
        if(file.isEmpty())throw new ApiException(400,"不能上传空文件");if(file.getSize()>max)throw new ApiException(413,"文件超过该类型大小限制");
        if(source.equals("camera")&&!mime.startsWith("image/"))throw new ApiException(400,"拍照入口仅接受照片");if(source.equals("screen")&&!mime.startsWith("video/"))throw new ApiException(400,"录屏入口仅接受视频");
        long used=0;for(Map<String,Object> existing:store.all("file"))if(s(existing,"ownerId").equals(s(actor,"id")))used+=((Number)existing.get("size")).longValue();if(used+file.getSize()>2L*1024*1024*1024)throw new ApiException(413,"个人附件空间已达2GB，请联系管理员处理");
        byte[] signature=new byte[16];int read;try(InputStream stream=file.getInputStream()){read=stream.read(signature);}
        if(ext.equals("txt")||ext.equals("csv"))checkText(file);
        else if(!signatureMatches(signature,read,ext))throw new ApiException(400,"文件实际类型与扩展名不匹配");
        if(ext.equals("docx")||ext.equals("xlsx"))checkOffice(file,ext);
        Path root=Paths.get(directory).toAbsolutePath().normalize();Files.createDirectories(root);String fileId=id(),stored=fileId+"."+ext;Path destination=root.resolve(stored).normalize();if(!destination.startsWith(root))throw new ApiException(400,"无效存储路径");
        // UUID disk names never contain user input; files are only served after business authorization.
        try(InputStream stream=file.getInputStream();OutputStream output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)){
            byte[] buffer=new byte[8192];long total=0;int n;while((n=stream.read(buffer))!=-1){total+=n;if(total>max)throw new ApiException(413,"文件超过大小限制");output.write(buffer,0,n);}
        }catch(Exception e){Files.deleteIfExists(destination);throw e;}
        Map<String,Object> metadata=map("id",fileId,"name",original,"size",file.getSize(),"contentType",mime,"source",source,"ownerId",s(actor,"id"),"ownerName",s(actor,"name"),"storedName",stored,"createdAt",now(),"scanStatus","not_configured");
        queue.enqueue(metadata);
        try{store.save("file",metadata);auth.audit(actor,"file.upload","附件编号 "+fileId+"，"+file.getSize()+"字节，来源"+source);}catch(RuntimeException e){Files.deleteIfExists(destination);throw e;}
        return map("code",0,"data",publicFile(metadata));
    }
    @GetMapping("/files/{id}/download") public ResponseEntity<Resource> download(@PathVariable String id,HttpServletRequest request) throws Exception {
        Map<String,Object> actor=auth.require(request),file=store.get("file",id);business.ensure(canRead(actor,file));
        if(!s(file,"processingStatus").equals("ready"))throw new ApiException(409,"文件尚未通过安全检查，历史文件须先重新检查后才能下载");
        if(!s(file,"expiresAt").isEmpty()&&java.time.Instant.parse(s(file,"expiresAt")).isBefore(java.time.Instant.now()))throw new ApiException(410,"文件已超过允许使用期限");
        Path root=Paths.get(directory).toAbsolutePath().normalize(),path=root.resolve(s(file,"previewStoredName").isEmpty()?s(file,"storedName"):s(file,"previewStoredName")).normalize();if(!path.startsWith(root)||!Files.isRegularFile(path)||Files.isSymbolicLink(path))throw new ApiException(404,"文件不存在");
        auth.audit(actor,"file.download","附件编号 "+id+"；用途：授权业务材料阅览；图片/PDF带账号水印");String downloadName=s(file,"name");if(s(file,"previewContentType").equals("video/mp4"))downloadName=downloadName.replaceFirst("\\.[^.]+$",".mp4");String encoded=URLEncoder.encode(downloadName,"UTF-8").replace("+","%20"),mime=s(file,"previewContentType").isEmpty()?s(file,"contentType"):s(file,"previewContentType");
        byte[] marked=watermark.render(path,mime,s(actor,"id").substring(0,Math.min(12,s(actor,"id").length()))+" | "+now());Resource resource=marked==null?new FileSystemResource(path):new org.springframework.core.io.ByteArrayResource(marked);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).contentLength(resource.contentLength()).header("Content-Disposition","attachment; filename*=UTF-8''"+encoded).header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").body(resource);
    }
    @GetMapping("/files/{id}") public Map<String,Object> status(@PathVariable String id,HttpServletRequest request){Map<String,Object> actor=auth.require(request),file=store.get("file",id);business.ensure(canRead(actor,file));return map("code",0,"data",publicFile(file));}
    @PostMapping("/files/{id}/recheck") @Transactional public Map<String,Object> recheck(@PathVariable String id,HttpServletRequest request){Map<String,Object> actor=auth.require(request);auth.writeAllowed(actor);Map<String,Object> file=store.get("file",id);business.ensure(s(file,"ownerId").equals(s(actor,"id"))||s(actor,"role").equals("admin"));Path root=Paths.get(directory).toAbsolutePath().normalize(),path=root.resolve(s(file,"storedName")).normalize();if(!path.startsWith(root)||!Files.isRegularFile(path)||Files.isSymbolicLink(path))throw new ApiException(404,"文件不存在");if(!Arrays.asList("ready","queued","processing").contains(s(file,"processingStatus"))){queue.enqueue(file);store.save("file",file);auth.audit(actor,"file.recheck","附件编号 "+id);}return map("code",0,"data",publicFile(file));}
    private void checkText(MultipartFile file)throws IOException {
        try {
            String value=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(file.getBytes())).toString();
            for(int i=0;i<value.length();i++){char c=value.charAt(i);if((c<32&&c!='\t'&&c!='\n'&&c!='\r')||c==127)throw new ApiException(400,"文本文件含二进制或控制字符，请保存为UTF-8文本后重试");}
        } catch(java.nio.charset.CharacterCodingException invalid){throw new ApiException(400,"TXT / CSV 请保存为UTF-8编码后上传");}
    }
    private boolean signatureMatches(byte[] value,int n,String ext){
        if(n<4)return false;
        if(ext.equals("jpg")||ext.equals("jpeg"))return (value[0]&255)==255&&(value[1]&255)==216&&(value[2]&255)==255;
        if(ext.equals("png"))return n>=8&&Arrays.equals(Arrays.copyOf(value,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
        if(ext.equals("pdf"))return n>=5&&new String(value,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        if(ext.equals("docx")||ext.equals("xlsx"))return value[0]==80&&value[1]==75&&value[2]==3&&value[3]==4;
        if(ext.equals("webm"))return (value[0]&255)==26&&(value[1]&255)==69&&(value[2]&255)==223&&(value[3]&255)==163;
        return n>=12&&((value[4]=='f'&&value[5]=='t'&&value[6]=='y'&&value[7]=='p')||(ext.equals("mov")&&((value[4]=='m'&&value[5]=='o'&&value[6]=='o'&&value[7]=='v')||(value[4]=='w'&&value[5]=='i'&&value[6]=='d'&&value[7]=='e'))));
    }
    private void checkOffice(MultipartFile file,String ext) throws IOException {
        boolean types=false,document=false;long expanded=0;int entries=0;byte[] buffer=new byte[8192];
        try(ZipInputStream zip=new ZipInputStream(file.getInputStream())){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
            if(++entries>3000)throw new ApiException(400,"文档结构过于复杂");String name=entry.getName();if(name.contains("..")||name.startsWith("/")||name.contains("\\"))throw new ApiException(400,"文档包含不安全路径");if(name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"))throw new ApiException(400,"不支持含宏的文档");
            if(name.equals("[Content_Types].xml"))types=true;if(name.equals(ext.equals("docx")?"word/document.xml":"xl/workbook.xml"))document=true;
            int n;while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>100L*1024*1024)throw new ApiException(400,"文档解压大小超过限制");}zip.closeEntry();
        }}catch(java.util.zip.ZipException e){throw new ApiException(400,"文档压缩结构损坏");}
        if(!types||!document)throw new ApiException(400,"文件不是有效的Office文档");
    }
}
