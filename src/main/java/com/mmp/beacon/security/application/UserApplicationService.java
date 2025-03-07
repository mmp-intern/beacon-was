package com.mmp.beacon.security.application;

import com.mmp.beacon.beacon.domain.repository.BeaconRepository;
import com.mmp.beacon.company.domain.Company;
import com.mmp.beacon.company.domain.repository.CompanyRepository;
import com.mmp.beacon.security.presentation.request.AdminCreateRequest;
import com.mmp.beacon.security.presentation.request.CreateUserRequest;
import com.mmp.beacon.security.presentation.request.LoginRequest;
import com.mmp.beacon.security.presentation.request.UpdateUserRequest;
import com.mmp.beacon.security.provider.JwtTokenProvider;
import com.mmp.beacon.security.query.response.UserProfileResponse;
import com.mmp.beacon.user.domain.*;
import com.mmp.beacon.user.domain.repository.AbstractUserRepository;
import com.mmp.beacon.user.domain.repository.AdminRepository;
import com.mmp.beacon.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.mmp.beacon.beacon.domain.Beacon;

import java.util.stream.Collectors;
import java.util.ArrayList;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserApplicationService {

    private static final Long FIXED_COMPANY_ID = 1L; // 고정된 회사 ID

    private final AdminRepository adminRepository;
    private final UserRepository userRepository;
    private final AbstractUserRepository abstractUserRepository;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final CompanyRepository companyRepository;
    private final BeaconRepository beaconRepository;
    private final Map<String, String> refreshTokenStore = new HashMap<>();

    // 현재 인증된 사용자 정보를 반환
    public AbstractUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof CustomUserDetails) {
            CustomUserDetails userDetail = (CustomUserDetails) auth.getPrincipal();
            return abstractUserRepository.findByUserIdAndIsDeletedFalse(userDetail.getUsername())
                    .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));
        }
        return null;
    }

    @Transactional
    public void updateUserProfile(String userId, UpdateUserRequest request) {
        AbstractUser user = abstractUserRepository.findByUserIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        if (user instanceof User) {
            List<Beacon> newBeacons = new ArrayList<>();
            List<Beacon> currentBeacons = beaconRepository.findByUserAndIsDeletedFalse((User) user);
            for (Beacon beacon : currentBeacons) {
                beacon.assignUser(null);
                beaconRepository.save(beacon);
            }

            if (request.getMacAddr() != null && !request.getMacAddr().isEmpty()) {
                for (String macAddr : request.getMacAddr()) {
                    Beacon beacon = beaconRepository.findByMacAddrAndIsDeletedFalse(macAddr)
                            .orElseThrow(() -> new IllegalArgumentException("비콘을 찾을 수 없습니다."));

                    beacon.assignUser((User) user);
                    beaconRepository.save(beacon);
                    newBeacons.add(beacon);
                }
            }

            String encPassword = request.getPassword() != null && !request.getPassword().isEmpty()
                    ? bCryptPasswordEncoder.encode(request.getPassword())
                    : null;

            ((User) user).updateProfile(
                    request.getName(),
                    request.getEmail(),
                    request.getPhone(),
                    request.getPosition(),
                    encPassword,
                    newBeacons.isEmpty() ? null : newBeacons
            );
            abstractUserRepository.save(user);
        } else {
            throw new IllegalArgumentException("지원되지 않는 사용자 유형입니다.");
        }
    }

    @Transactional(readOnly = true)
    public Page<UserProfileResponse> getAllAdmins(int page, int size, String searchTerm, String searchBy) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Admin> adminPage;

        if (searchTerm != null && !searchTerm.isEmpty()) {
            if ("id".equalsIgnoreCase(searchBy)) {
                adminPage = adminRepository.findByUserIdContainingAndIsDeletedFalse(searchTerm, pageable);
            } else {
                throw new IllegalArgumentException("유효하지 않은 검색 기준입니다. 'id'만 허용됩니다.");
            }
        } else {
            adminPage = adminRepository.findAllByIsDeletedFalse(pageable);
        }

        return adminPage.map(this::createUserProfileResponse);
    }

    @Transactional(readOnly = true)
    public Page<UserProfileResponse> getAllUsers(int page, int size, String searchTerm, String searchBy) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> userPage;

        if (searchTerm != null && !searchTerm.isEmpty()) {
            if ("id".equalsIgnoreCase(searchBy)) {
                userPage = userRepository.findByUserIdContainingAndIsDeletedFalse(searchTerm, pageable);
            } else if ("name".equalsIgnoreCase(searchBy)) {
                userPage = userRepository.findByNameContainingAndIsDeletedFalse(searchTerm, pageable);
            } else {
                throw new IllegalArgumentException("유효하지 않은 검색 기준입니다. 'id' 또는 'name'만 허용됩니다.");
            }
        } else {
            userPage = userRepository.findAllByIsDeletedFalse(pageable);
        }

        return userPage.map(this::createUserProfileResponse);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String userId) {
        AbstractUser currentUser = getCurrentUser();
        AbstractUser user = abstractUserRepository.findByUserIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new UsernameNotFoundException("ID가 " + userId + "인 사용자를 찾을 수 없습니다."));

        if (currentUser.getUserId().equals(user.getUserId())) {
            return createUserProfileResponse(user);
        }

        if (currentUser.getRole() == UserRole.SUPER_ADMIN) {
            return createUserProfileResponse(user);
        }

        if (currentUser.getRole() == UserRole.ADMIN || currentUser.getRole() == UserRole.USER) {
            if (user.getRole() == UserRole.USER) {
                Company currentUserCompany = getUserCompany(currentUser);
                Company userCompany = getUserCompany(user);

                if (currentUserCompany != null && userCompany != null && currentUserCompany.equals(userCompany)) {
                    return createUserProfileResponse(user);
                }
            }
        }
        throw new IllegalArgumentException("같은 회사의 사용자만 조회할 수 있습니다.");
    }

    private Company getUserCompany(AbstractUser user) {
        return companyRepository.findByIdAndIsDeletedFalse(FIXED_COMPANY_ID)
                .orElseThrow(() -> new IllegalArgumentException("회사를 찾을 수 없습니다."));
    }

    @Transactional
    public void register(CreateUserRequest userDto) {
        try {
            String encPassword = bCryptPasswordEncoder.encode(userDto.getPassword());
            UserRole role = UserRole.USER;

            AbstractUser currentUser = getCurrentUser();
            if (currentUser == null) {
                throw new IllegalArgumentException("인증이 필요합니다.");
            }

            Company company = companyRepository.findByIdAndIsDeletedFalse(FIXED_COMPANY_ID)
                    .orElseThrow(() -> new IllegalArgumentException("회사를 찾을 수 없습니다."));

            AbstractUser user = new User(userDto.getUserId(), encPassword, role, company, userDto.getName(), userDto.getEmail(), userDto.getPhone(), userDto.getPosition());
            abstractUserRepository.save(user);

            if (userDto.getBeaconIds() != null && !userDto.getBeaconIds().isEmpty()) {
                for (String beaconIdStr : userDto.getBeaconIds()) {
                    Long beaconId = Long.parseLong(beaconIdStr);
                    Beacon beacon = beaconRepository.findByIdAndIsDeletedFalse(beaconId)
                            .filter(b -> b.getUser() == null || b.getUser().equals(user))
                            .orElseThrow(() -> new IllegalArgumentException("비콘을 찾을 수 없습니다."));

                    beacon.assignUser((User) user);
                    beaconRepository.save(beacon);
                }
            }

            log.info("사용자 등록 성공: {}", userDto.getUserId());
        } catch (DataIntegrityViolationException e) {
            log.error("중복된 항목 오류: ", e);
            throw new DataIntegrityViolationException("중복된 항목 오류: " + userDto.getUserId());
        } catch (Exception e) {
            log.error("등록 실패: ", e);
            throw new RuntimeException("등록 실패: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void registerAdmin(AdminCreateRequest adminDto) {
        String encPassword = bCryptPasswordEncoder.encode(adminDto.getPassword());
        UserRole role = UserRole.ADMIN;

        AbstractUser currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new IllegalArgumentException("인증이 필요합니다.");
        }

        Company company = companyRepository.findByIdAndIsDeletedFalse(FIXED_COMPANY_ID)
                .orElseThrow(() -> new IllegalArgumentException("회사를 찾을 수 없습니다."));

        AbstractUser user = new Admin(adminDto.getUserId(), encPassword, role, company);

        abstractUserRepository.save(user);
        log.info("관리자 등록 성공: {}", adminDto.getUserId());
    }

    @Transactional
    public Map<String, String> authenticate(LoginRequest userDto) {
        log.info("사용자 인증 중: {}", userDto.getUserId());
        Optional<AbstractUser> userOpt = abstractUserRepository.findByUserIdAndIsDeletedFalse(userDto.getUserId());

        if (userOpt.isPresent() && bCryptPasswordEncoder.matches(userDto.getPassword(), userOpt.get().getPassword())) {
            AbstractUser user = userOpt.get();
            Authentication auth = new UsernamePasswordAuthenticationToken(new CustomUserDetails(user), null, user.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            String accessToken = jwtTokenProvider.generateToken((CustomUserDetails) auth.getPrincipal());
            String refreshToken = jwtTokenProvider.generateRefreshToken((CustomUserDetails) auth.getPrincipal());

            refreshTokenStore.put(user.getUserId(), refreshToken);

            log.info("인증 성공: {}", user.getUserId());
            Map<String, String> tokens = new HashMap<>();
            tokens.put("accessToken", accessToken);
            tokens.put("refreshToken", refreshToken);

            return tokens;
        }
        log.warn("인증 실패: {}", userDto.getUserId());
        return null;
    }

    public String refreshAccessToken(String refreshToken) {
        if (jwtTokenProvider.validateToken(refreshToken)) {
            String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
            String storedRefreshToken = refreshTokenStore.get(username);

            if (storedRefreshToken != null && storedRefreshToken.equals(refreshToken)) {
                Optional<AbstractUser> userOpt = abstractUserRepository.findByUserIdAndIsDeletedFalse(username);
                if (userOpt.isPresent()) {
                    return jwtTokenProvider.generateToken(new CustomUserDetails(userOpt.get()));
                }
            }
        }
        throw new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다.");
    }

    @Transactional
    public void deleteUser(String userId) {
        AbstractUser currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new IllegalArgumentException("인증이 필요합니다.");
        }

        UserRole currentUserRole = currentUser.getRole();
        if (currentUserRole != UserRole.SUPER_ADMIN && currentUserRole != UserRole.ADMIN) {
            throw new IllegalArgumentException("권한이 부족합니다: 사용자를 삭제할 수 없습니다.");
        }

        AbstractUser userToDelete = abstractUserRepository.findByUserIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        log.info("삭제 요청된 사용자 ID: {}", userId);
        if (currentUserRole == UserRole.ADMIN) {
            if (userToDelete.getRole() != UserRole.USER) {
                throw new IllegalArgumentException("권한이 부족합니다: 이 사용자를 삭제할 수 없습니다.");
            }
        }

        if (userToDelete instanceof User) {
            List<Beacon> userBeacons = beaconRepository.findByUserAndIsDeletedFalse((User) userToDelete);
            for (Beacon beacon : userBeacons) {
                beacon.assignUser(null);
                beaconRepository.save(beacon);
            }
        }

        userToDelete.delete();
        abstractUserRepository.save(userToDelete);
        log.info("사용자 삭제 성공: {}", userId);
    }

    private UserProfileResponse createUserProfileResponse(AbstractUser user) {
        List<Long> beaconIds = null;
        List<String> macAddrs = new ArrayList<>();
        if (user instanceof User) {
            User specificUser = (User) user;

            List<Beacon> beacons = beaconRepository.findByUserAndIsDeletedFalse(specificUser);
            if (!beacons.isEmpty()) {
                beaconIds = beacons.stream()
                        .map(Beacon::getId)
                        .collect(Collectors.toList());
                macAddrs = beacons.stream()
                        .map(Beacon::getMacAddr)
                        .collect(Collectors.toList());
            }

            return new UserProfileResponse(
                    user.getId(),
                    user.getUserId(),
                    specificUser.getEmail(),
                    specificUser.getPosition(),
                    specificUser.getName(),
                    specificUser.getPhone(),
                    FIXED_COMPANY_ID,
                    specificUser.getCompany().getName(),
                    user.getRole().name(),
                    beaconIds,
                    !macAddrs.isEmpty() ? macAddrs : null
            );
        } else if (user instanceof Admin) {
            Admin specificAdmin = (Admin) user;
            return new UserProfileResponse(
                    user.getId(),
                    user.getUserId(),
                    null,
                    null,
                    null,
                    null,
                    FIXED_COMPANY_ID,
                    specificAdmin.getCompany().getName(),
                    user.getRole().name(),
                    null,
                    null
            );
        } else if (user instanceof SuperAdmin) {
            return new UserProfileResponse(
                    user.getId(),
                    user.getUserId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    user.getRole().name(),
                    null,
                    null
            );
        } else {
            return null;
        }
    }
}
