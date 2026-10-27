#include "game/terrain_sync.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#ifdef ZZ_NET_INTEGRATION
#include "game/net.h"
#include "game/items.h"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
// Isola somente os dados da arma; o transporte, os sockets e a validação são reais.
const zz::WeaponDef& zz::weaponDef(int) {
    static const WeaponDef rifle={FireKind::Hitscan, ReticleType::Cross,18,.1f,30,2,100,0,
        ProjectileBehavior::DirectDamage,5,2,.68f,0,1,.9f,.6f,1.25f};
    return rifle;
}
#endif
using namespace zz;
static void check(bool ok, const char* what) {
    if (!ok) { std::fprintf(stderr, "FAIL: %s\n", what); std::exit(1); }
}
int main() {
    terrainwire::Journal host;
    terrainwire::Receiver client;
    host.reset(42); client.reset(42);
    for (int i=0; i<500; ++i)
        check(host.record({i%128, 9, i/128, B_AIR}), "host records edits");
    auto first = host.batch(0);
    check(first.size() <= 1400 && first.size() > 14, "edits fit UDP MTU");
    check(client.accept(first.data(), first.size()), "first contiguous batch accepted");
    check(client.revision() == 198, "first packet holds exactly 198 edits");
    auto edits=client.take();
    check(edits.size()==198 && edits[197].x==69 && edits[197].z==1,
          "decode preserves coordinates and order");
    check(client.accept(first.data(), first.size()) && client.take().empty(),
          "lost ACK causes duplicate batch without duplicate application");
    auto gap=host.batch(396);
    check(!client.accept(gap.data(), gap.size()) && client.revision()==198,
          "out of order batch cannot advance cumulative ACK");
    auto overlap=host.batch(100);
    check(client.accept(overlap.data(), overlap.size()) && client.revision()==298,
          "overlapping retransmission applies only unseen suffix");
    check(client.take().size()==100, "overlap adds only new edits");
    while(client.revision()<host.revision()) {
        auto retry=host.batch(client.revision());
        check(client.accept(retry.data(), retry.size()), "slow client catches up from durable journal");
        client.take();
    }
    check(client.revision()==500 && host.batch(500).empty(), "full cumulative ACK stops sending");
    client.reset(42);
    check(client.accept(first.data(),first.size()) && client.take().size()==198,
          "reconnected client can replay journal from revision zero");
    auto invalid=first;
    invalid[4]=43;
    check(!client.accept(invalid.data(),invalid.size()), "different match seed rejected");
    invalid=first; invalid[14]=128;
    check(!client.accept(invalid.data(),invalid.size()) && client.take().empty(),
          "out of arena coordinate rejects entire batch before mutation");
    invalid=first; invalid[20]=B_COUNT;
    check(!client.accept(invalid.data(),invalid.size()), "unknown block rejected");
    invalid=host.batch(198); invalid.back()=B_COUNT;
    check(!client.accept(invalid.data(),invalid.size()) && client.revision()==198 && client.take().empty(),
          "invalid suffix rejects all preceding valid new edits atomically");
    invalid=first; invalid.pop_back();
    check(!client.accept(invalid.data(),invalid.size()), "truncated packet rejected");
    invalid=first; invalid.push_back(0);
    check(!client.accept(invalid.data(),invalid.size()), "oversized packet rejected");
    invalid=first; invalid[2]=2;
    check(!client.accept(invalid.data(),invalid.size()), "previous protocol rejected");
    invalid=first; std::memset(invalid.data()+8,0,4);
    check(!client.accept(invalid.data(),invalid.size()), "revision zero rejected");
    check(!host.record({-1,9,0,B_AIR}) && !host.record({1,128,0,B_AIR}) &&
          !host.record({0,0,128,B_AIR}) && !host.record({0,0,0,B_COUNT}), "invalid host edits rejected");
    auto ack=terrainwire::ackPacket(42,3,198);
    int id=-1; uint32_t revision=0;
    check(terrainwire::readAck(ack.data(),ack.size(),42,id,revision) && id==3 && revision==198,
          "ACK carries match participant and cumulative revision");
    check(!terrainwire::readAck(ack.data(),ack.size(),43,id,revision), "wrong match ACK rejected");
    check(!terrainwire::readAck(ack.data(),ack.size()-1,42,id,revision), "truncated ACK rejected");
    uint32_t accepted=100;
    check(!terrainwire::advanceAck(accepted,300,250) && accepted==100, "cannot ACK unsent edits");
    check(terrainwire::advanceAck(accepted,198,250) && accepted==198, "valid cumulative ACK advances");
    check(terrainwire::advanceAck(accepted,99,250) && accepted==198, "old ACK cannot roll revision back");
    host.reset(99); client.reset(99);
    check(host.revision()==0 && client.revision()==0 && client.take().empty(), "new match clears journal and receive queue");
    check(!client.accept(first.data(),first.size()), "old match cannot repopulate new world");
#ifdef ZZ_NET_INTEGRATION
    NetConfig hc; hc.host=true; hc.seed=42; hc.skins[0]=hc.skins[1]=0;
    NetConfig cc=hc; cc.host=false; cc.localId=1; cc.hostIp="127.0.0.1";
    NetSession authority, peer;
    std::vector<Vec3> spawns={{32,20,32},{40,20,32}};
    check(authority.start(hc,spawns) && peer.start(cc,spawns), "loopback sessions start");
    std::vector<NetEvent> events;
    int hostShots=0,clientShots=0;
    authority.setWorldShot([&](const Vec3&,const Vec3&) {
        ++hostShots; authority.recordTerrainEdit(hostShots,9,1,B_AIR);
    });
    peer.setWorldShot([&](const Vec3&,const Vec3&) { ++clientShots; });
    authority.localFire({32,21.3f,32},{42,21.3f,32});
    authority.update(.05f,events);
    check(hostShots==1, "validated local host shot invokes terrain callback");
    authority.localFire({1000,21,32},{42,21,32});
    authority.update(.05f,events);
    check(hostShots==1, "invalid local host shot cannot edit terrain");
    peer.localFire({40,21.3f,32},{50,21.3f,32});
    for(int i=0;i<14;++i) { peer.update(.05f,events); authority.update(.05f,events); usleep(10000); }
    check(hostShots==2 && clientShots==0, "remote shot invokes callback only on authority once");
    auto received=peer.takeTerrainEdits();
    check(received.size()==2 && received[0].x==1 && received[1].x==2, "client receives confirmed host edits");
    peer.recordTerrainEdit(88,9,1,B_AIR);
    for(int i=0;i<5;++i) { authority.update(.05f,events); peer.update(.05f,events); }
    check(peer.takeTerrainEdits().empty() && authority.takeTerrainEdits().empty(),
          "duplicate delivery and client attempts cannot produce more edits");
    authority.recordTerrainEdit(-1,9,0,B_AIR);
    authority.recordTerrainEdit(0,128,0,B_AIR);
    for(int i=0;i<5;++i) { authority.update(.05f,events); peer.update(.05f,events); }
    check(peer.takeTerrainEdits().empty(), "invalid authoritative coordinates never enter journal");
    const int attacker=::socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
    sockaddr_in destination{};
    destination.sin_family=AF_INET; destination.sin_port=htons(kGamePort);
    inet_pton(AF_INET,"127.0.0.1",&destination.sin_addr);
    const uint32_t senderRejects=authority.guardStats().rejected[pvp::SENDER];
    auto spoofedAck=terrainwire::ackPacket(42,1,2);
    sendto(attacker,spoofedAck.data(),spoofedAck.size(),0,
           reinterpret_cast<const sockaddr*>(&destination),sizeof(destination));
    authority.update(.05f,events);
    check(authority.guardStats().rejected[pvp::SENDER]==senderRejects+1,
          "ACK from different source port cannot acknowledge participant edits");
    const uint32_t formatRejects=authority.guardStats().rejected[pvp::FORMAT];
    spoofedAck=terrainwire::ackPacket(43,1,2);
    sendto(attacker,spoofedAck.data(),spoofedAck.size(),0,
           reinterpret_cast<const sockaddr*>(&destination),sizeof(destination));
    authority.update(.05f,events);
    check(authority.guardStats().rejected[pvp::FORMAT]==formatRejects+1,
          "ACK from another match rejected before changing progress");
    terrainwire::Journal forgedWorld; forgedWorld.reset(42);
    for(int i=0;i<3;++i) forgedWorld.record({100+i,9,1,B_AIR});
    auto forgedEdits=forgedWorld.batch(2);
    const uint32_t peerRejects=peer.guardStats().rejected[pvp::SENDER];
    const sockaddr_in clientEndpoint=authority.player(1).addr;
    sendto(attacker,forgedEdits.data(),forgedEdits.size(),0,
           reinterpret_cast<const sockaddr*>(&clientEndpoint),sizeof(clientEndpoint));
    peer.update(.05f,events);
    check(peer.guardStats().rejected[pvp::SENDER]==peerRejects+1 && peer.takeTerrainEdits().empty(),
          "forged edits from host IP with wrong port never enter client world");
    ::close(attacker);
    peer.stop();
    for(int i=0;i<130;++i) authority.update(.05f,events);
    check(!authority.player(1).active, "disconnected participant times out");
    check(peer.start(cc,spawns), "participant can start new client socket for same match");
    for(int i=0;i<12;++i) { peer.update(.05f,events); authority.update(.05f,events); usleep(10000); }
    received=peer.takeTerrainEdits();
    check(received.size()==2 && received[0].x==1 && received[1].x==2,
          "reconnected client replays all edits even after effects expired");
    authority.stop(); peer.stop();
    check(peer.takeTerrainEdits().empty(), "stop clears confirmed receive queue");
    std::puts("PASS: real UDP host/client shot authority and durable terrain delivery");
#endif
    std::puts("PASS: terrain wire batching, retransmission, overlap, reconnect, seed, bounds and ACK validation");
}
