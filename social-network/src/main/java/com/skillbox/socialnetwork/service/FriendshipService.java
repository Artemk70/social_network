package com.skillbox.socialnetwork.service;

import com.skillbox.socialnetwork.api.request.IsFriends;
import com.skillbox.socialnetwork.api.response.AccountResponse;
import com.skillbox.socialnetwork.api.response.ListResponse;
import com.skillbox.socialnetwork.api.response.authdto.AuthData;
import com.skillbox.socialnetwork.api.response.friendsdto.FriendsResponse200;
import com.skillbox.socialnetwork.api.response.friendsdto.friendsornotfriends.ResponseFriendsList;
import com.skillbox.socialnetwork.api.response.friendsdto.friendsornotfriends.StatusFriend;
import com.skillbox.socialnetwork.api.response.socketio.AuthorData;
import com.skillbox.socialnetwork.api.response.socketio.SocketNotificationData;
import com.skillbox.socialnetwork.entity.Friendship;
import com.skillbox.socialnetwork.entity.FriendshipStatus;
import com.skillbox.socialnetwork.entity.NotificationSetting;
import com.skillbox.socialnetwork.entity.Person;
import com.skillbox.socialnetwork.entity.enums.FriendshipStatusCode;
import com.skillbox.socialnetwork.entity.enums.NotificationType;
import com.skillbox.socialnetwork.exception.*;
import com.skillbox.socialnetwork.repository.FriendshipRepository;
import com.skillbox.socialnetwork.repository.FriendshipStatusRepository;
import com.skillbox.socialnetwork.repository.NotificationSettingRepository;
import com.skillbox.socialnetwork.repository.PersonRepository;
import io.jsonwebtoken.lang.Strings;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.skillbox.socialnetwork.service.AuthService.setAuthData;
import static com.skillbox.socialnetwork.service.AuthService.setDeletedAuthData;
import static java.time.ZoneOffset.UTC;

@Slf4j
@Service
@AllArgsConstructor
public class FriendshipService {
    private final NotificationSettingRepository notificationSettingRepository;
    private final PersonRepository personRepository;
    private final FriendshipRepository friendshipRepository;
    private final FriendshipStatusRepository friendshipStatusRepository;
    private final NotificationService notificationService;
    private final CacheManager cacheManager;

    public ListResponse<AuthData> getFriends(String name, int offset, int itemPerPage, Principal principal) {
        log.debug("?????????? ?????????????????? ????????????");
        Person person = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));
        Pageable pageable = PageRequest.of(offset / itemPerPage, itemPerPage);
        Page<Person> friendsPage = personRepository.findFriends(name, person.getId(), pageable);
        return getPersonResponse(offset, itemPerPage, friendsPage);
    }

    public FriendsResponse200 stopBeingFriendsById(int id, Principal principal) throws FriendshipNotFoundException {
        log.debug("?????????? ???????????????? ???? ????????????");

        Person srcPerson = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));
        Person dstPerson = personRepository.findById(id).orElseThrow(EntityNotFoundException::new);
        Friendship friendship = friendshipRepository.findFriendBySrcPersonAndDstPerson(srcPerson.getId(), dstPerson.getId()).orElseThrow(FriendshipNotFoundException::new);
        friendship.setSrcPerson(dstPerson)
                .setDstPerson(srcPerson)
                .setStatus(friendshipStatusRepository
                        .save(friendship.getStatus()).setCode(FriendshipStatusCode.SUBSCRIBED));
        friendshipRepository.save(friendship);

        return getFriendResponse200("Stop being friends");
    }

    /**
     * CacheEvictAble(value = "recommendedPersonsCache", key = "#email")
     */
    public FriendsResponse200 addNewFriend(int id, Principal principal) throws DeletedAccountException, AddingOrSubscribingOnBlockerPersonException, AddingYourselfToFriends, FriendshipExistException, AddingOrSubscribingOnBlockedPersonException {
        log.debug("?????????? ???????????????????? ?? ????????????");

        Person srcPerson = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));

        if (srcPerson.getId() == id) {
            throw new AddingYourselfToFriends("???????????? ???????????????? ???????? ?? ????????????");
        }
        Person dstPerson = personRepository.findById(id).orElseThrow(EntityNotFoundException::new);
        if (dstPerson.isDeleted()) {
            throw new DeletedAccountException("This Account was deleted");
        }
        Optional<Friendship> friendshipOptional = friendshipRepository.findRequestFriendship(srcPerson.getId(), dstPerson.getId());

        if (isBlockedBy(dstPerson.getId(), srcPerson.getId(), friendshipOptional)) {
            throw new AddingOrSubscribingOnBlockerPersonException("This Person Blocked You");
        }
        if (isBlockedBy(srcPerson.getId(), dstPerson.getId(), friendshipOptional)) {
            throw new AddingOrSubscribingOnBlockedPersonException("You Blocked this Person");
        }

        /*
          Src ?????????????????? Dst
          ???????? ?????????????? ??????, ???? ?????????????????? ?? ?????????? ?? ?????????? REQUEST
          ???????? ???????????? ???????? ?? ???? REQUEST, ???? ???????????? ?????? ???? FRIEND
         */

        if (friendshipOptional.isPresent()) {
            if (friendshipOptional.get().getStatus().getCode().equals(FriendshipStatusCode.FRIEND))
                throw new FriendshipExistException();
            if (friendshipOptional.get().getSrcPerson().getId().equals(dstPerson.getId()))
                friendshipStatusRepository
                        .save(friendshipOptional.get().getStatus()
                                .setTime(LocalDateTime.now())
                                .setCode(FriendshipStatusCode.FRIEND));
            else throw new AddingYourselfToFriends("?????? ??????????????????????????");

        } else {
            Friendship newFriendship = new Friendship();
            newFriendship.setStatus(friendshipStatusRepository.save(new FriendshipStatus()
                            .setTime(LocalDateTime.now())
                            .setCode(FriendshipStatusCode.REQUEST)))
                    .setSrcPerson(srcPerson)
                    .setDstPerson(dstPerson);
            friendshipRepository.save(newFriendship);
            Cache recommendations = cacheManager.getCache("recommendedPersonsCache");
            if (recommendations != null) {
                recommendations.evict(srcPerson.getEMail());
                recommendations.evict(dstPerson.getEMail());
            }
            //Notification
            if (notificationSettingRepository.findNotificationSettingByPersonId(newFriendship.getDstPerson().getId())
                    .orElse(new NotificationSetting().setFriendsRequest(true)).isFriendsRequest()) {
                notificationService.createNotification(newFriendship.getDstPerson(), newFriendship.getId(), NotificationType.FRIEND_REQUEST);
                sendNotification(newFriendship);
            }
            //Notification
        }
        return getFriendResponse200("Adding to friends");
    }

    public ListResponse<AuthData> getFriendsRequests(String name, int offset, int itemPerPage, Principal principal) {
        Person person = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));
        Pageable pageable = PageRequest.of(offset / itemPerPage, itemPerPage);
        Page<Person> personByStatusCode = personRepository
                .findPersonByStatusCode(name, person.getId(), FriendshipStatusCode.REQUEST, pageable);
        return getPersonResponse(offset, itemPerPage, personByStatusCode);
    }

    @Cacheable(value = "recommendedPersonsCache", key = "#principal.getName")
    public ListResponse<AuthData> recommendedUsers(int offset, int itemPerPage, Principal principal) {
        log.info("?????????? ?????????????????? ?????????????????????????????? ???????????? ?????? ???????????????????????? {} ", principal.getName());
        Person person = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));
        log.info("?????????? ?????????????????????????????? ???????????? ?????? ????????????????????????: ".concat(person.getFirstName()));
        Pageable pageable = PageRequest.of(offset / itemPerPage, itemPerPage);
        LocalDate startDate = null;
        LocalDate stopDate = null;
        //???? ?????????? ?? ?????????????????????????? ??????????????????????, ????????????, ???????? ?????????????????? ?????????????? ?? ???????????? ?? ???? ???????? ????????????????
        List<Integer> blockers = personRepository.findPersonRelastionShips(person.getId());
        blockers.add(person.getId());
        if (person.getBirthday() != null) {
            //?????????????????? ??????????????????????????, ?????????????? ?????????????? ???????????????????? ???? +-2 ????????
            LocalDate birthdayPerson = person.getBirthday();
            startDate = birthdayPerson.minusYears(2);
            stopDate = birthdayPerson.plusYears(2);
        }
        // ???????????????? ?????? ?????????????? ???? ????????????
        String city = Strings.hasText(person.getCity()) ? person.getCity() : "";
        Page<Person> personFirstPage = personRepository.findByOptionalParametrs(
                "", "", startDate, stopDate, city, "", pageable, blockers);

        if ((int) personFirstPage.getTotalElements() < 10) {
            Pageable additionalPageable = PageRequest.of(0, (int) (10 - personFirstPage.getTotalElements()));
            personFirstPage.get().forEach(p -> blockers.add(p.getId()));
            Page<Person> additionalPersonPage = get10Users(person.getEMail(), additionalPageable, blockers);
            List<Person> additionalPersonList = additionalPersonPage.stream().toList();
            List<Person> personFirstList = personFirstPage.stream().collect(Collectors.toList());
            personFirstList.addAll(additionalPersonList);
            personFirstPage = new PageImpl<>(personFirstList, pageable, personFirstList.size());
        }
        return getPersonResponse(offset, itemPerPage, personFirstPage);

    }

    public ResponseFriendsList isPersonsFriends(IsFriends isFriends, Principal principal) {
        log.debug("?????????? ???????????????? ???????????????? ???? ???????????????????? ???????? ????????????????");
        Person person = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));

        List<StatusFriend> statusFriendList = new ArrayList<>();

        for (int friendId : isFriends.getUserIds())
            statusFriendList.add(new StatusFriend(friendId, friendshipRepository
                    .isMyFriend(person.getId(), friendId, FriendshipStatusCode.FRIEND)
                    .orElseThrow(() -> new UsernameNotFoundException("user not found"))));


        ResponseFriendsList responseFriendsList = new ResponseFriendsList();
        responseFriendsList.setData(statusFriendList);

        return responseFriendsList;
    }

    //==========================================================================================================
    private ListResponse<AuthData> getPersonResponse(int offset, int itemPerPage, Page<Person> pageablePersonList) {
        ListResponse<AuthData> postResponse = new ListResponse<>();
        postResponse.setPerPage(itemPerPage);
        postResponse.setTimestamp(LocalDateTime.now().toInstant(UTC));
        postResponse.setOffset(offset);
        postResponse.setTotal((int) pageablePersonList.getTotalElements());
        postResponse.setData(getPerson4Response(pageablePersonList.toList()));
        return postResponse;
    }


    private List<AuthData> getPerson4Response(List<Person> persons) {
        List<AuthData> personDataList = new ArrayList<>();
        persons.forEach(person -> {
            AuthData personData;
            if (person.isDeleted()) {
                personData = setDeletedAuthData(person);
            } else {
                personData = setAuthData(person);
            }
            personDataList.add(personData);
        });
        return personDataList;
    }

    private FriendsResponse200 getFriendResponse200(String message) {
        FriendsResponse200 response = new FriendsResponse200();
        response.setTimestamp(LocalDateTime.now());
        response.setError("Successfully");
        response.setMessage(message);
        return response;
    }

    /**
     * Src Person BlOCKED Dest Person or
     * Dest person WASBLOCKEDBY Src Person or
     * Src and Dest blocked Each other (DEADLOCK)
     * <p>
     * CacheEvictAble(value = "recommendedPersonsCache", key = "#email")
     */
    public AccountResponse blockUser(Principal principal, int id) throws BlockAlreadyExistsException, UserBlocksHimSelfException, BlockingDeletedAccountException {
        Person current = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));
        if (current.getId() == id) throw new UserBlocksHimSelfException();
        Person blocking = personRepository.findById(id).orElseThrow(EntityNotFoundException::new);
        if (blocking.isDeleted()) throw new BlockingDeletedAccountException();
        Optional<Friendship> optional = friendshipRepository.findFriendshipBySrcPersonAndDstPerson(id, current.getId());
        if (!isBlockedBy(current.getId(), blocking.getId(), optional)) {
            if (optional.isEmpty()) {
                createFriendship(current, blocking, FriendshipStatusCode.BLOCKED);
                Cache recommendations = cacheManager.getCache("recommendedPersonsCache");
                if (recommendations != null) {
                    recommendations.evict(blocking.getEMail());
                    recommendations.evict(current.getEMail());
                }
            } else {
                Friendship friendship = optional.get();
                FriendshipStatus friendshipStatus = friendship.getStatus();
                if (friendshipStatus.getCode().equals(FriendshipStatusCode.WASBLOCKEDBY) && friendship.getSrcPerson().getId().equals(current.getId())
                        || friendshipStatus.getCode().equals(FriendshipStatusCode.BLOCKED) && friendship.getDstPerson().getId().equals(current.getId())
                ) {
                    friendshipStatus.setCode(FriendshipStatusCode.DEADLOCK);
                } else if (current.getId().equals(friendship.getSrcPerson().getId())) {
                    friendshipStatus.setCode(FriendshipStatusCode.BLOCKED);
                } else {
                    friendshipStatus.setCode(FriendshipStatusCode.WASBLOCKEDBY);
                }
                friendshipStatusRepository.save(friendshipStatus);
                friendshipRepository.save(friendship);
            }
        } else {
            throw new BlockAlreadyExistsException();

        }
        return getSuccessAccountResponse();
    }

    public void createFriendship(Person src, Person dest, FriendshipStatusCode friendshipStatusCode) {
        FriendshipStatus friendshipStatus = new FriendshipStatus();
        friendshipStatus.setTime(LocalDateTime.now());
        friendshipStatus.setCode(friendshipStatusCode);
        FriendshipStatus saveFriendshipStatus = friendshipStatusRepository.save(friendshipStatus);
        Friendship newFriendship = new Friendship();
        newFriendship.setStatus(saveFriendshipStatus);
        newFriendship.setSrcPerson(src);
        newFriendship.setDstPerson(dest);
        friendshipRepository.save(newFriendship);

    }

    public AccountResponse unBlockUser(Principal principal, int id) throws UnBlockingException, UserUnBlocksHimSelfException, UnBlockingDeletedAccountException {
        Person current = personRepository.findByEMail(principal.getName()).orElseThrow(() -> new BadCredentialsException("???????????? ????????????????"));
        if (current.getId() == id) throw new UserUnBlocksHimSelfException();
        Person unblocking = personRepository.findById(id).orElseThrow(EntityNotFoundException::new);
        if (unblocking.isDeleted()) throw new UnBlockingDeletedAccountException();
        Optional<Friendship> optional = friendshipRepository.findFriendshipBySrcPersonAndDstPerson(current.getId(), id);
        if (!isBlockedBy(current.getId(), id, optional)) {
            throw new UnBlockingException();
        }
        Friendship friendship = optional.orElseThrow(EntityNotFoundException::new);
        if (friendship.getStatus().getCode().equals(FriendshipStatusCode.BLOCKED)
                || friendship.getStatus().getCode().equals(FriendshipStatusCode.WASBLOCKEDBY) && current.getId().equals(friendship.getDstPerson().getId())) {
            friendshipRepository.delete(friendship);
            friendshipStatusRepository.delete(friendship.getStatus());
            Cache recommendations = cacheManager.getCache("recommendedPersonsCache");
            if (recommendations != null) {
                recommendations.evict(unblocking.getEMail());
                recommendations.evict(current.getEMail());
            }
        } else {
            if (current.getId().equals(friendship.getSrcPerson().getId())) {
                friendship.getStatus().setCode(FriendshipStatusCode.WASBLOCKEDBY);
            } else {
                friendship.getStatus().setCode(FriendshipStatusCode.BLOCKED);
            }
            friendshipStatusRepository.save(friendship.getStatus());
            friendshipRepository.save(friendship);

        }
        return getSuccessAccountResponse();

    }

    static AccountResponse getSuccessAccountResponse() {
        AccountResponse accountResponse = new AccountResponse();
        accountResponse.setTimestamp(ZonedDateTime.now().toInstant());
        Map<String, String> dateMap = new HashMap<>();
        dateMap.put("message", "ok");
        accountResponse.setData(dateMap);
        return accountResponse;
    }

    /**
     * Src Person BlOCKED Dest Person or
     * Dest person WASBLOCKEDBY Src Person or
     * Src and Dest blocked Each other (DEADLOCK)
     */
    public boolean isBlockedBy(int blocker, int blocked) {
        Optional<Friendship> optional = friendshipRepository.findFriendshipBySrcPersonAndDstPerson(blocker, blocked);
        return isBlockedBy(blocker, blocked, optional);
    }

    private boolean isBlockedBy(int blocker, int blocked, Optional<Friendship> optional) {
        return optional.filter(friendship -> (blocker == friendship.getSrcPerson().getId() && friendship.getStatus().getCode().equals(FriendshipStatusCode.BLOCKED))
                || (blocked == friendship.getSrcPerson().getId() && friendship.getStatus().getCode().equals(FriendshipStatusCode.WASBLOCKEDBY))
                || friendship.getStatus().getCode().equals(FriendshipStatusCode.DEADLOCK)).isPresent();
    }

    public Page<Person> get10Users(String email, Pageable pageable, List<Integer> blockers) {
        return personRepository.find10Person(email, pageable, blockers);
    }

    private void sendNotification(Friendship friendship) {
        SocketNotificationData notificationData = new SocketNotificationData();
        notificationData.setId(friendship.getId())
                .setSentTime(Instant.now())
                .setEventType(NotificationType.FRIEND_REQUEST)
                .setEntityAuthor(new AuthorData().setPhoto(friendship.getSrcPerson().getPhoto())
                        .setLastName(friendship.getSrcPerson().getLastName())
                        .setFirstName(friendship.getSrcPerson().getFirstName())
                        .setId(friendship.getSrcPerson().getId()))
                .setEntityId(friendship.getSrcPerson().getId());
        notificationService.sendEvent("friend-notification-response", notificationData, friendship.getDstPerson().getId());

    }

    public List<Integer> getFriendsAndFriendsOfFriendsAndSubscribesFiltered(int id) {
        HashSet<Integer> blockersIds = new HashSet<>(personRepository.findBlockersIds(id));
        List<Integer> friendsAndFriendsOfFriendsAndSubscribesIds = personRepository.findFriendsAndFriendsOfFriendsAndSubscribesIds(id);
        List<Integer> filtered = new ArrayList<>();
        for (Integer fr : friendsAndFriendsOfFriendsAndSubscribesIds) {
            if (!blockersIds.contains(fr)) filtered.add(fr);
        }
        return filtered;
    }

}

