package com.web.service;

import com.web.config.Environment;
import com.web.constants.LogUtils;
import com.web.dto.CustomerScheduleVnpay;
import com.web.dto.PaymentRequest;
import com.web.entity.*;
import com.web.enums.CustomerSchedulePay;
import com.web.enums.PayType;
import com.web.enums.StatusCustomerSchedule;
import com.web.enums.UserType;
import com.web.exception.MessageException;
import com.web.models.ApproveCustomerScheduleRequest;
import com.web.models.ApproveCustomerScheduleResponse;
import com.web.models.CreateScheduleGuestRequest;
import com.web.models.CreateScheduleGuestResponse;
import com.web.models.ListCustomerScheduleRequest;
import com.web.models.ListCustomerScheduleResponse;
import com.web.models.QueryStatusTransactionResponse;
import com.web.processor.QueryTransactionStatus;
import com.web.repository.*;
import com.web.utils.MailService;
import com.web.utils.UserUtils;
import com.web.vnpay.VNPayService;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.persistence.criteria.Predicate;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Component
public class CustomerScheduleService {

    @Autowired
    private CustomerScheduleRepository customerScheduleRepository;

    @Autowired
    private UserUtils userUtils;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private VaccineScheduleRepository vaccineScheduleRepository;

    @Autowired
    private VaccineScheduleTimeRepository vaccineScheduleTimeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VNPayService vnPayService;

    public CustomerSchedule create(CustomerSchedule customerSchedule, String orderId, String requestId) {
        LogUtils.init();
        if (paymentRepository.findByOrderIdAndRequestId(orderId, requestId).isPresent()) {
            throw new MessageException("Không được thực hiện hành động này");
        }
        Environment environment = Environment.selectEnv("dev");
        try {
            QueryStatusTransactionResponse queryStatusTransactionResponse = QueryTransactionStatus.process(environment, orderId, requestId);
            System.out.println("qqqq-----------------------------------------------------------" + queryStatusTransactionResponse.getMessage());
            if (queryStatusTransactionResponse.getResultCode() != 0) {
                throw new MessageException("Chưa được thanh toán");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new MessageException("Chưa được thanh toán");
        }
        User user = userUtils.getUserWithAuthority();
        customerSchedule.setUser(user);
        customerSchedule.setCreatedDate(new Timestamp(System.currentTimeMillis()));
        customerSchedule.setStatusCustomerSchedule(StatusCustomerSchedule.pending);
        customerScheduleRepository.save(customerSchedule);

        VaccineScheduleTime vaccineScheduleTime = vaccineScheduleTimeRepository.findById(customerSchedule.getVaccineScheduleTime().getId()).get();
        Payment payment = new Payment();
        payment.setCreatedDate(new Timestamp(System.currentTimeMillis()));
        payment.setCreatedBy(user);
        payment.setCustomerSchedule(customerSchedule);
        payment.setAmount(vaccineScheduleTime.getVaccineSchedule().getVaccine().getPrice());
        payment.setOrderId(orderId);
        payment.setRequestId(requestId);
        paymentRepository.save(payment);
        return customerSchedule;
    }

    public Page<CustomerSchedule> mySchedule(Pageable pageable, String search,Date from,Date to) {
        User user = userUtils.getUserWithAuthority();
        if(search == null){
            search = "";
        }
        search = "%"+search+"%";
        if(from == null || to == null){
            from = Date.valueOf("2000-01-01");
            to = Date.valueOf("2100-01-01");
        }
        Page<CustomerSchedule> list = customerScheduleRepository.findByUser(user.getId(), search,from, to, pageable);
        return list;
    }

    public void cancel(Long id) {
        Optional<CustomerSchedule> customerSchedule = customerScheduleRepository.findById(id);
        if (customerSchedule.isEmpty()) {
            throw new MessageException("Không tìm thấy lịch đã đăng ký");
        }
        if (customerSchedule.get().getUser().getId() != userUtils.getUserWithAuthority().getId()) {
            throw new MessageException("Bạn không đủ quyền");
        }
        if (customerSchedule.get().getStatusCustomerSchedule().equals(StatusCustomerSchedule.cancelled)) {
            throw new MessageException("Không được lặp lại hành động");
        }
        customerSchedule.get().setStatusCustomerSchedule(StatusCustomerSchedule.cancelled);
        customerScheduleRepository.save(customerSchedule.get());
    }

    public Page<ListCustomerScheduleResponse> listCustomerSchedule(ListCustomerScheduleRequest request) {

        if (ObjectUtils.isEmpty(request)) {
            throw new MessageException(HttpStatus.BAD_REQUEST.value(), "Đã có lỗi");
        }

        Pageable pageable = PageRequest.of(request.getPage() - 1, request.getLimit());
        Page<CustomerSchedule> customerSchedulePage = customerScheduleRepository.findAll(specificationCustomerScheduleList(request), pageable);

        List<ListCustomerScheduleResponse> list = customerSchedulePage.stream().map(e ->
                {
                    Optional<User> user = userRepository.findById(e.getUser().getId());
                    Optional<VaccineScheduleTime> vaccineScheduleTime = vaccineScheduleTimeRepository.findById(e.getVaccineScheduleTime().getId());
                    return ListCustomerScheduleResponse.builder()
                            .id(e.getId())
                            .status(e.getStatusCustomerSchedule().name())
                            .fullName(e.getFullName())
                            .createdDate(e.getCreatedDate())
                            .vaccineScheduleTime(vaccineScheduleTime.orElse(null))
                            .user(user.orElse(null))
                            .build();
                }
        ).toList();
        return new PageImpl<>(list, pageable, customerSchedulePage.getTotalElements());

    }

    public Specification<CustomerSchedule> specificationCustomerScheduleList(ListCustomerScheduleRequest requestBody) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.isNotEmpty(requestBody.getFullName())) {
                String searchValue = "%" + requestBody.getFullName() + "%";
                predicates.add(criteriaBuilder.like(root.get("fullName"), searchValue));
            }
            if (ObjectUtils.isNotEmpty(requestBody.getStatus())) {
                predicates.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("statusCustomerSchedule"), StatusCustomerSchedule.valueOf(requestBody.getStatus()))));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateScheduleGuestResponse createScheduleGuest(CreateScheduleGuestRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new MessageException(HttpStatus.BAD_REQUEST.value(), "Đã có lỗi");
        }
        Optional<User> optionalUser = userRepository.findByEmail(request.getEmail());
        if (optionalUser.isPresent()) {
            throw new MessageException(HttpStatus.BAD_REQUEST.value(), "Email đã tồn tại");
        }
        Optional<User> optionalUserPhone = userRepository.findByPhoneNumber(request.getPhone());
        if (optionalUserPhone.isPresent()) {
            throw new MessageException(HttpStatus.BAD_REQUEST.value(), "Số điện thoại đã tồn tại");
        }
        User user = User.builder()
                .email(request.getEmail())
                .createdDate(new Date(System.currentTimeMillis()))
                .phoneNumber(request.getPhone())
                .password("123")
                .userType(UserType.standard)
                .build();
        userRepository.save(user);
        Optional<VaccineScheduleTime> optionalVaccineScheduleTime = vaccineScheduleTimeRepository.findById(request.getVaccineScheduleId());

        CustomerSchedule customerSchedule = CustomerSchedule.builder()
                .statusCustomerSchedule(StatusCustomerSchedule.pending)
                .fullName(request.getFullName())
                .vaccineScheduleTime(optionalVaccineScheduleTime.orElse(null))
                .phone(request.getPhone())
                .address(request.getAddress())
                .createdDate(new Timestamp(System.currentTimeMillis()))
                .user(user)
                .build();
        customerScheduleRepository.save(customerSchedule);
        sendEmailToCustomer(customerSchedule);
        return CreateScheduleGuestResponse.builder()
                .vaccineScheduleTime(optionalVaccineScheduleTime.orElse(null))
                .createdDate(customerSchedule.getCreatedDate())
                .fullName(customerSchedule.getFullName())
                .id(customerSchedule.getId())
                .user(user)
                .status(customerSchedule.getStatusCustomerSchedule().name())
                .build();
    }

    public ApproveCustomerScheduleResponse approveCustomerSchedule(ApproveCustomerScheduleRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new MessageException(HttpStatus.BAD_REQUEST.value(), "Đã có lỗi");
        }
        Optional<CustomerSchedule> optionalVaccineSchedule = customerScheduleRepository.findById(request.getCustomerScheduleId());
        if (ObjectUtils.isEmpty(optionalVaccineSchedule)) {
            throw new MessageException(HttpStatus.BAD_REQUEST.value(), "Lịch tiêm cảu khách không tồn tại");
        }
        CustomerSchedule customerSchedule = optionalVaccineSchedule.get();
        customerSchedule.setStatusCustomerSchedule(request.getStatus().equals("confirmed") ? StatusCustomerSchedule.confirmed : StatusCustomerSchedule.cancelled);
        customerScheduleRepository.save(customerSchedule);
        sendEmailToCustomer(customerSchedule);
        return ApproveCustomerScheduleResponse.builder().status(request.getStatus()).build();
    }

    private void sendEmailToCustomer(CustomerSchedule customerSchedule) {
        // Thông tin email
        String to = customerSchedule.getUser().getEmail();
        String subject = "Thông báo về lịch tiêm";
        String body = "Lịch tiêm của bạn đã được "
                + (customerSchedule.getStatusCustomerSchedule() == StatusCustomerSchedule.confirmed ? "xác nhận"
                : (customerSchedule.getStatusCustomerSchedule() == StatusCustomerSchedule.pending ? "tạo" : "hủy")) + ".";

        // Thiết lập gửi email
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.host", "smtp.gmail.com"); // Địa chỉ máy chủ SMTP
        properties.put("mail.smtp.port", "587"); // Cổng máy chủ SMTP

        Session session = Session.getInstance(properties, new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("cphuoc281@gmail.com", "xzcq pwwv vahl dilr"); // Thay đổi thông tin xác thực
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress("cphuoc281@gmail.com"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setText(body);

            Transport.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    public CustomerSchedule createNotPay(CustomerSchedule customerSchedule) {
        CustomerSchedule result = save(customerSchedule, null, null);
        return result;
    }

    public CustomerSchedule createVnPay(CustomerScheduleVnpay customerScheduleVnpay) {
        if(paymentRepository.findByOrderIdAndRequestId(customerScheduleVnpay.getVnpOrderInfo(),customerScheduleVnpay.getVnpOrderInfo()).isPresent()){
            throw new MessageException("Lịch đặt đã được thanh toán");
        }
        int paymentStatus = vnPayService.orderReturnByUrl(customerScheduleVnpay.getVnpayUrl());
        if(paymentStatus != 1){
            throw new MessageException("Thanh toán thất bại");
        }
        CustomerSchedule result = save(customerScheduleVnpay.getCustomerSchedule(), PayType.VNPAY, customerScheduleVnpay.getVnpOrderInfo());
        return result;
    }

    public CustomerSchedule createMomo(CustomerSchedule customerSchedule,String orderId,String requestId) {
        LogUtils.init();
        if (paymentRepository.findByOrderIdAndRequestId(orderId, requestId).isPresent()) {
            throw new MessageException("Không được thực hiện hành động này");
        }
        Environment environment = Environment.selectEnv("dev");
        try {
            QueryStatusTransactionResponse queryStatusTransactionResponse = QueryTransactionStatus.process(environment, orderId, requestId);
            System.out.println("qqqq-----------------------------------------------------------" + queryStatusTransactionResponse.getMessage());
            if (queryStatusTransactionResponse.getResultCode() != 0) {
                throw new MessageException("Chưa được thanh toán");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new MessageException("Chưa được thanh toán");
        }
        CustomerSchedule result = save(customerSchedule, PayType.MOMO, orderId);

        return result;
    }

    public CustomerSchedule save(CustomerSchedule customerSchedule, PayType payType, String orderId){
        VaccineScheduleTime vaccineScheduleTime = vaccineScheduleTimeRepository.findById(customerSchedule.getVaccineScheduleTime().getId()).get();
        Long count = customerScheduleRepository.countBySchedule(vaccineScheduleTime.getId());
        if (count == null){
            count = 0L;
        }
        if(count + 1 > vaccineScheduleTime.getLimitPeople()){
            throw new MessageException("Ca tiêm này đã đủ");
        }
        User user = userUtils.getUserWithAuthority();
        customerSchedule.setUser(user);
        customerSchedule.setCreatedDate(new Timestamp(System.currentTimeMillis()));
        customerSchedule.setStatusCustomerSchedule(StatusCustomerSchedule.pending);
        customerSchedule.setCustomerSchedulePay(CustomerSchedulePay.CHUA_THANH_TOAN);
        if(payType != null){
            if(payType.equals(PayType.VNPAY)){
                customerSchedule.setCustomerSchedulePay(CustomerSchedulePay.THANH_TOAN_VNPAY);
            }
            if(payType.equals(PayType.MOMO)){
                customerSchedule.setCustomerSchedulePay(CustomerSchedulePay.THANH_TOAN_MOMO);
            }
        }
        customerScheduleRepository.save(customerSchedule);

        if(payType != null){
            Payment payment = new Payment();
            payment.setCreatedDate(new Timestamp(System.currentTimeMillis()));
            payment.setCreatedBy(user);
            payment.setCustomerSchedule(customerSchedule);
            payment.setPayType(payType);
            payment.setOrderId(orderId);
            payment.setRequestId(orderId);
            payment.setAmount(vaccineScheduleTime.getVaccineSchedule().getVaccine().getPrice());
            paymentRepository.save(payment);
        }
        return customerSchedule;
    }

    public void finishPayment(Long id, PaymentRequest paymentRequest) {
        CustomerSchedule customerSchedule = customerScheduleRepository.findById(id).get();
        if(!customerSchedule.getCustomerSchedulePay().equals(CustomerSchedulePay.CHUA_THANH_TOAN)){
            throw new MessageException("Lịch này đã được thanh toán");
        }
        String orderId = null;
        if(paymentRequest.getPayType().equals(PayType.MOMO)){
            LogUtils.init();
            orderId = paymentRequest.getOrderId();
            Environment environment = Environment.selectEnv("dev");
            try {
                QueryStatusTransactionResponse queryStatusTransactionResponse = QueryTransactionStatus.process(environment, orderId, paymentRequest.getRequestId());
                if (queryStatusTransactionResponse.getResultCode() != 0) {
                    throw new MessageException("Chưa được thanh toán");
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new MessageException("Chưa được thanh toán");
            }
            customerSchedule.setCustomerSchedulePay(CustomerSchedulePay.THANH_TOAN_MOMO);
        }
        if(paymentRequest.getPayType().equals(PayType.VNPAY)){
            orderId = paymentRequest.getVnpOrderInfo();
            int paymentStatus = vnPayService.orderReturnByUrl(paymentRequest.getVnpayUrl());
            if(paymentStatus != 1){
                throw new MessageException("Thanh toán thất bại");
            }
            customerSchedule.setCustomerSchedulePay(CustomerSchedulePay.THANH_TOAN_VNPAY);
        }
        if(paymentRepository.findByOrderIdAndRequestId(orderId,orderId).isPresent()){
            throw new MessageException("Không hợp lệ");
        }
        customerScheduleRepository.save(customerSchedule);
    }


    public void change(Long id, Long timeId) {
        CustomerSchedule customerSchedule = customerScheduleRepository.findById(id).get();
        VaccineScheduleTime vaccineScheduleTime = vaccineScheduleTimeRepository.findById(timeId).get();

        // kiểm tra customerSchedule đã đăng ký sau 24 giờ chưa
        Instant createdDate = customerSchedule.getCreatedDate().toInstant();
        Instant now = Instant.now();

        if (Duration.between(createdDate, now).toHours() > 24) {
            throw new MessageException("Đã quá "+Duration.between(createdDate, now).toHours()+"h, không thể đổi được lịch tiêm");
        }

        customerSchedule.setVaccineScheduleTime(vaccineScheduleTime);
        customerScheduleRepository.save(customerSchedule);
    }
}