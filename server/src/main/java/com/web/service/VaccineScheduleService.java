package com.web.service;

import com.web.entity.Center;
import com.web.entity.CustomerSchedule;
import com.web.entity.Vaccine;
import com.web.entity.VaccineSchedule;
import com.web.exception.MessageException;
import com.web.repository.CustomerScheduleRepository;
import com.web.repository.VaccineRepository;
import com.web.repository.VaccineScheduleRepository;
import com.web.repository.VaccineScheduleTimeRepository;
import com.web.utils.MailService;
import com.web.utils.UserUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class VaccineScheduleService {

    @Autowired
    private VaccineScheduleRepository vaccineScheduleRepository;

    @Autowired
    private VaccineScheduleTimeRepository vaccineScheduleTimeRepository;

    @Autowired
    private CustomerScheduleRepository customerScheduleRepository;

    @Autowired
    private UserUtils userUtils;

    @Autowired
    private VaccineRepository vaccineRepository;

    @Autowired
    private MailService mailService;

    /*
    * api này dùng để thêm lịch tiêm vaccine
    * */
    public VaccineSchedule save(VaccineSchedule vaccineSchedule) {
        Vaccine vaccine = vaccineRepository.findById(vaccineSchedule.getVaccine().getId()).get();
        if(vaccine.getInventory() == null){
            throw new MessageException("Vaccine không đủ số lượng");
        }
        if(vaccine.getInventory() < vaccineSchedule.getLimitPeople()){
            throw new MessageException("Vaccine không đủ số lượng, chỉ còn "+ vaccine.getInventory());
        }
        vaccineSchedule.setCreatedDate(new Timestamp(System.currentTimeMillis()));
        vaccineSchedule.setUser(userUtils.getUserWithAuthority());
        vaccineScheduleRepository.save(vaccineSchedule);
        vaccine.setInventory(vaccine.getInventory() - vaccineSchedule.getLimitPeople());
        vaccineRepository.save(vaccine);

        if(vaccineSchedule.getIdPreSchedule() != null){
            Optional<VaccineSchedule> vc = vaccineScheduleRepository.findById(vaccineSchedule.getIdPreSchedule());
            if(vc.isPresent()){
                List<CustomerSchedule> list = customerScheduleRepository.findByVaccineSchedule(vc.get().getId());
                for(CustomerSchedule c : list){
                    mailService.sendEmail(c.getUser().getEmail(),"Thông báo mũi tiêm tiếp theo",
                            "Mũi tiêm "+c.getVaccineScheduleTime().getVaccineSchedule().getVaccine().getName()+" đã có lịch tiêm tiếp theo<br>"+
                            "Thời gian tiêm mũi tiếp theo từ ngày: "+vaccineSchedule.getStartDate()+" đến ngày: "+vaccineSchedule.getEndDate()+"<br>"+
                            vaccineSchedule.getDescription()
                            , false, true);
                }
            }
        }
        return vaccineSchedule;
    }


    /*
     * api này dùng để cập nhật lịch tiêm vaccine
     * */
    public VaccineSchedule update(VaccineSchedule vaccineSchedule) {
        Vaccine vaccine = vaccineRepository.findById(vaccineSchedule.getVaccine().getId()).get();
        if(vaccine.getInventory() == null){
            throw new MessageException("Vaccine không đủ số lượng");
        }
        if(vaccine.getInventory() < vaccineSchedule.getLimitPeople()){
            throw new MessageException("Vaccine không đủ số lượng, chỉ còn "+ vaccine.getInventory());
        }
        if (vaccineSchedule.getId() == null){
            throw new MessageException("Id không được null");
        }
        Optional<VaccineSchedule> exist = vaccineScheduleRepository.findById(vaccineSchedule.getId());
        if (exist.isEmpty()){
            throw new MessageException("Không tìm thấy lịch tiêm có id: "+vaccineSchedule.getId());
        }
        Long num = vaccineScheduleTimeRepository.quantityBySchedule(vaccineSchedule.getId());
        if(num == null){
            num = 0L;
        }
        if(num > vaccineSchedule.getLimitPeople()){
            throw new MessageException("Số lượng mũi tiêm đã phát hành là: "+ num+", số mũi tiêm bạn cập nhật không chính xác");
        }
        vaccineSchedule.setCreatedDate(exist.get().getCreatedDate());
        vaccineSchedule.setUser(exist.get().getUser());
        vaccineScheduleRepository.save(vaccineSchedule);
        vaccine.setInventory(vaccine.getInventory() + exist.get().getLimitPeople() - vaccineSchedule.getLimitPeople());
        vaccineRepository.save(vaccine);
        return vaccineSchedule;
    }

    /*
     * api này dùng để xóa lịch tiêm vaccine
     * */
    public void delete(Long id){
        try {
            VaccineSchedule vaccineSchedule = vaccineScheduleRepository.findById(id).get();
            Vaccine vaccine = vaccineSchedule.getVaccine();
            Integer limitPeople = vaccineSchedule.getLimitPeople();
            vaccineScheduleRepository.deleteById(id);
            vaccine.setInventory(vaccine.getInventory() + limitPeople);
            vaccineRepository.save(vaccine);
        }
        catch (Exception e){
            throw new MessageException("Lịch tiêm này đã được đăng ký, không thể xóa");
        }
    }

    /*
     * api này dùng để lấy danh sách lịch tiêm vaccine, truyền vào ngày bắt đầu và kết thúc
     * nếu không truyền ngày bd hoặc kt thì lấy mặc định tất cả
     * */

    public Page<VaccineSchedule> vaccineSchedules(Date from, Date to, Pageable pageable){
        Page<VaccineSchedule> page = null;
        if (from == null || to == null) {
            page = vaccineScheduleRepository.findAll(pageable);
        }
        else{
            page = vaccineScheduleRepository.findByDate(from, to , pageable);
        }
        return page;
    }

    public List<VaccineSchedule> findByVacxin(Long vacxinId) {
        LocalDateTime now = LocalDateTime.now();
        List<VaccineSchedule> list = vaccineScheduleRepository.findByVacxin(vacxinId, now);
        return list;
    }

    public VaccineSchedule findById(Long id) {
        Optional<VaccineSchedule> vaccineSchedule = vaccineScheduleRepository.findById(id);
        if (vaccineSchedule.isEmpty()) {
            throw new MessageException("Không tìm thấy lịch tiêm với id: "+id);
        }
        return vaccineSchedule.get();
    }

    public Page<VaccineSchedule> nextSchedule(String param, Pageable pageable){
        if(param == null){
            param = "";
        }
        param = "%"+param+"%";
        Page<VaccineSchedule> page = vaccineScheduleRepository.findByParam(param, LocalDateTime.now(), pageable);
        for(VaccineSchedule v : page.getContent()){
            if(customerScheduleRepository.countRegis(v.getId()) < v.getLimitPeople()){
                v.setInStock(true);
            }
        }
        return page;
    }

    public Page<VaccineSchedule> preSchedule(String param, Pageable pageable){
        if(param == null){
            param = "";
        }
        param = "%"+param+"%";
        Page<VaccineSchedule> page = vaccineScheduleRepository.preFindByParam(param, LocalDateTime.now(), pageable);
        return page;
    }

    public List<VaccineSchedule> list(){
        return vaccineScheduleRepository.findAll();
    }


    public List<VaccineSchedule> getCenter(Date start, Long vaccineId) {
        List<VaccineSchedule> list = new ArrayList<>();
        if(start == null){
            start = new Date(System.currentTimeMillis());
        }
        if(start.toLocalDate().isBefore(LocalDate.now())){
            throw new MessageException("Thời gian tối thiểu phải bắt đầu từ ngày hiện tại");
        }
        list = vaccineScheduleRepository.getCenter(start, vaccineId);
        return list;
    }
}
